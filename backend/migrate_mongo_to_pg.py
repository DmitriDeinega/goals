"""One-shot MongoDB → PostgreSQL migration.

Reads the live Mongo database and writes it into the Postgres schema, remapping
ObjectId primary keys onto the new BIGINT identity columns.

Usage (from the repo root, with the Mongo instance reachable):

    pip install motor pymongo
    python backend/migrate_mongo_to_pg.py \
        --mongo-uri mongodb://localhost:27017 \
        --mongo-db goals_db \
        --database-url postgresql://goals:goals@localhost:5433/goals_db

Add --dry-run to report what would be written without touching Postgres.

Safe to re-run: it TRUNCATEs the target tables first, so the result is always a
faithful copy of Mongo rather than a merge.

── The ID remap ─────────────────────────────────────────────────────────────
Goals keyed by 24-char ObjectId hex strings become BIGINT identity values.
`goal_weeks.goal_id` and `logs.goal_id` hold those hex strings as references,
so every one must be translated through the same map.

Orphans matter here. Deleting a goal in the old app removed the goal document
but deliberately kept *past* weeks' goal_weeks rows and logs, which render from
the frozen snapshot. Those rows reference ObjectIds with no surviving goal, so
the map is seeded from every id seen anywhere — not just from the goals
collection — and orphans get synthetic ids that preserve their history.
"""

import argparse
import asyncio
import json
import sys
from datetime import date

try:
    from motor.motor_asyncio import AsyncIOMotorClient
except ImportError:
    sys.exit("motor is required for migration: pip install motor pymongo")

import asyncpg


def to_date(value):
    """Mongo stored dates as 'YYYY-MM-DD' strings; Postgres wants date objects."""
    if value is None:
        return None
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        return date.fromisoformat(value)
    # datetime → date
    return value.date()


def normalize_reward_rules(rules):
    """Keep only the two fields the schema documents, coercing types."""
    out = []
    for r in rules or []:
        if not isinstance(r, dict):
            continue
        try:
            out.append({
                "min_completions": int(r["min_completions"]),
                "reward_amount": float(r["reward_amount"]),
            })
        except (KeyError, TypeError, ValueError):
            continue
    return out


def normalize_goal_shape(gtype, times_per_week, times_per_day):
    """The goals_shape CHECK requires exactly one cadence field set, matching
    the type. Mongo had no such constraint, so stale values may linger on
    documents whose type was changed. Clear the irrelevant one and supply a
    default if the relevant one is missing."""
    if gtype == "daily":
        tpd = times_per_day if isinstance(times_per_day, int) and times_per_day >= 1 else 1
        return None, tpd
    tpw = times_per_week if isinstance(times_per_week, int) and 1 <= times_per_week <= 7 else 1
    return tpw, None


async def migrate(mongo_uri, mongo_db, database_url, dry_run=False):
    mongo = AsyncIOMotorClient(mongo_uri)
    mdb = mongo[mongo_db]

    # ── Read everything from Mongo ────────────────────────────────────────
    goals = await mdb.goals.find({}).sort("order", 1).to_list(None)
    goal_weeks = await mdb.goal_weeks.find({}).to_list(None)
    logs = await mdb.logs.find({}).to_list(None)
    settings = await mdb.settings.find_one({"_id": "global"})
    devices = await mdb.devices.find({}).to_list(None)
    seq_doc = await mdb.sequence.find_one({"_id": "global"})
    seq = (seq_doc or {}).get("seq", 0)

    print(f"Read from Mongo: {len(goals)} goals, {len(goal_weeks)} goal_weeks, "
          f"{len(logs)} logs, {len(devices)} devices, seq={seq}")

    # ── Build the ObjectId → BIGINT map ───────────────────────────────────
    # Live goals first, in `order`, so new ids follow the display order.
    id_map = {}
    next_id = 1
    for g in goals:
        id_map[str(g["_id"])] = next_id
        next_id += 1

    # Then any id referenced by history but with no surviving goal.
    orphans = set()
    for gw in goal_weeks:
        gid = str(gw.get("goal_id", ""))
        if gid and gid not in id_map:
            orphans.add(gid)
    for lg in logs:
        gid = str(lg.get("goal_id", ""))
        if gid and gid not in id_map:
            orphans.add(gid)
    for gid in sorted(orphans):
        id_map[gid] = next_id
        next_id += 1

    if orphans:
        print(f"  {len(orphans)} orphaned goal ids (deleted goals with surviving "
              f"history) mapped to preserve past weeks")

    if dry_run:
        print("\n--- DRY RUN — nothing written ---")
        print(f"  goals      -> {len(goals)} rows")
        print(f"  goal_weeks -> {len(goal_weeks)} rows")
        print(f"  logs       -> {len(logs)} rows")
        print(f"  devices    -> {len(devices)} rows")
        print(f"  settings   -> {'1 row' if settings else 'MISSING (seed manually)'}")
        print(f"  sequence   -> {seq}")
        print(f"  next goal id would be {next_id}")
        mongo.close()
        return

    # ── Write to Postgres ─────────────────────────────────────────────────
    conn = await asyncpg.connect(database_url)
    await conn.set_type_codec(
        "jsonb", encoder=json.dumps, decoder=json.loads, schema="pg_catalog"
    )

    try:
        async with conn.transaction():
            await conn.execute("TRUNCATE goals, goal_weeks, logs, devices RESTART IDENTITY")

            # Goals
            goal_rows = []
            for g in goals:
                gtype = g.get("type")
                if gtype not in ("daily", "weekly_x"):
                    print(f"  ! skipping goal {g.get('_id')} with unknown type {gtype!r}")
                    continue
                tpw, tpd = normalize_goal_shape(
                    gtype, g.get("times_per_week"), g.get("times_per_day")
                )
                goal_rows.append((
                    id_map[str(g["_id"])],
                    (g.get("name") or "").strip(),
                    gtype,
                    bool(g.get("is_negative", False)),
                    tpw,
                    tpd,
                    normalize_reward_rules(g.get("reward_rules")),
                    int(g.get("order", 0)),
                    int(g.get("version", 1)) or 1,
                ))

            await conn.executemany(
                """
                INSERT INTO goals (id, name, type, is_negative, times_per_week,
                                   times_per_day, reward_rules, "order", version)
                VALUES ($1, $2, $3::goal_type, $4, $5, $6, $7::jsonb, $8, $9)
                """,
                goal_rows,
            )

            # goal_weeks — snapshot's embedded reward_rules get the same cleanup.
            gw_rows = []
            for gw in goal_weeks:
                gid = str(gw.get("goal_id", ""))
                if gid not in id_map:
                    continue
                snap = dict(gw.get("snapshot") or {})
                if "reward_rules" in snap:
                    snap["reward_rules"] = normalize_reward_rules(snap["reward_rules"])
                gw_rows.append((
                    id_map[gid],
                    to_date(gw["week_start"]),
                    bool(gw.get("enabled", True)),
                    snap,
                ))

            await conn.executemany(
                """
                INSERT INTO goal_weeks (goal_id, week_start, enabled, snapshot)
                VALUES ($1, $2, $3, $4::jsonb)
                ON CONFLICT (goal_id, week_start) DO NOTHING
                """,
                gw_rows,
            )

            # logs — drop rows with empty slots (the CHECK rejects them, and an
            # empty slot array carries no information).
            #
            # Slot values are validated STRICTLY rather than coerced: bool(s)
            # would turn None into False and the string "false" into True,
            # silently rewriting history. Anything that isn't a real bool is a
            # hard error so the operator can inspect it.
            log_rows = []
            skipped_logs = 0
            bad_slots = []
            for lg in logs:
                gid = str(lg.get("goal_id", ""))
                slots = lg.get("slots") or []
                if gid not in id_map or not slots:
                    skipped_logs += 1
                    continue
                if not all(isinstance(s, bool) for s in slots):
                    bad_slots.append((gid, lg.get("date"), slots))
                    continue
                log_rows.append((
                    id_map[gid],
                    to_date(lg["date"]),
                    list(slots),
                ))

            if bad_slots:
                for gid, d, slots in bad_slots[:10]:
                    print(f"  ! non-boolean slots: goal={gid} date={d} slots={slots!r}")
                raise SystemExit(
                    f"ABORT: {len(bad_slots)} log(s) contain non-boolean slot values. "
                    "Fix them in Mongo (or extend this script with an explicit "
                    "rule) rather than letting them be silently coerced."
                )

            await conn.executemany(
                """
                INSERT INTO logs (goal_id, date, slots)
                VALUES ($1, $2, $3)
                ON CONFLICT (goal_id, date) DO NOTHING
                """,
                log_rows,
            )

            # devices
            device_rows = [
                (
                    d["token"],
                    d.get("app_session_id"),
                    d.get("widget_session_id"),
                    d.get("platform") or "android",
                    d.get("last_seen_at"),
                )
                for d in devices if d.get("token")
            ]
            await conn.executemany(
                """
                INSERT INTO devices (token, app_session_id, widget_session_id, platform, last_seen_at)
                VALUES ($1, $2, $3, $4, COALESCE($5, now()))
                ON CONFLICT (token) DO NOTHING
                """,
                device_rows,
            )

            # settings — clear first, so a rerun against a Mongo without a
            # settings doc doesn't silently retain a previous run's timezone.
            if not settings:
                await conn.execute("DELETE FROM settings")
            if settings:
                await conn.execute(
                    """
                    INSERT INTO settings (id, timezone, first_day_of_week, currency, start_date)
                    VALUES (TRUE, $1, $2::first_day, $3, $4)
                    ON CONFLICT (id) DO UPDATE SET
                        timezone          = EXCLUDED.timezone,
                        first_day_of_week = EXCLUDED.first_day_of_week,
                        currency          = EXCLUDED.currency,
                        start_date        = EXCLUDED.start_date
                    """,
                    settings["timezone"],
                    settings["first_day_of_week"],
                    settings.get("currency") or "NIS",
                    to_date(settings.get("start_date")),
                )

            # sequence
            await conn.execute("UPDATE sequence SET seq = $1 WHERE id = TRUE", int(seq))

            # Advance the identity counter past the ids we inserted explicitly,
            # or the next INSERT would collide on id=1. ALTER TABLE can't take
            # a bind parameter, and next_id is a locally-derived int — never
            # client input — so interpolation is safe here.
            await conn.execute(
                f"ALTER TABLE goals ALTER COLUMN id RESTART WITH {int(next_id)}"
            )

        # Report ACTUAL inserted counts, not attempted ones — ON CONFLICT DO
        # NOTHING can silently drop duplicates, and "1603 logs" next to 1600
        # actual rows is exactly the kind of discrepancy worth surfacing.
        actual = {}
        for t in ("goals", "goal_weeks", "logs", "devices"):
            actual[t] = await conn.fetchval(f"SELECT count(*) FROM {t}")

        def line(label, attempted, table):
            got = actual[table]
            flag = "" if got == attempted else f"  <-- {attempted - got} NOT inserted"
            return f"  {label:<10} {got}{flag}"

        print(f"\nMigrated to Postgres:")
        print(line("goals", len(goal_rows), "goals"))
        print(line("goal_weeks", len(gw_rows), "goal_weeks"))
        print(line("logs", len(log_rows), "logs") + (f" ({skipped_logs} skipped)" if skipped_logs else ""))
        print(line("devices", len(device_rows), "devices"))
        print(f"  settings   {'ok' if settings else 'MISSING — run seed_settings.py'}")
        print(f"  sequence   {seq}")
        print(f"  next goal id = {next_id}")
    finally:
        await conn.close()
        mongo.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--mongo-uri", default="mongodb://localhost:27017")
    ap.add_argument("--mongo-db", default="goals_db")
    ap.add_argument("--database-url", default="postgresql://goals:goals@localhost:5433/goals_db")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    asyncio.run(migrate(args.mongo_uri, args.mongo_db, args.database_url, args.dry_run))
