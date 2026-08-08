from fastapi import APIRouter, HTTPException, Request
import asyncpg
import logging
from ..database import get_db
from ..models import GoalCreate, GoalUpdate, GoalOut, GoalWeekOut, GoalChangedPayload
from ..time_utils import get_today, get_week_start, get_week_end
from ..broadcaster import broadcast
from ..sequence import consume_client_seq, parse_client_seq
from ..rows import goal_from_row, goal_week_from_row, snapshot_from_row, to_date
from .logs import get_goal_week_logs, ensure_slots_for_goal, reconcile_slots_for_goal

router = APIRouter()
logger = logging.getLogger("goals.routers.goals")

GOAL_COLS = 'id, name, type, is_negative, times_per_week, times_per_day, reward_rules, "order", version'


async def get_settings_cached(conn):
    row = await conn.fetchrow(
        "SELECT timezone, first_day_of_week FROM settings WHERE id = TRUE"
    )
    if not row:
        raise RuntimeError("Settings not found in DB")
    return row["timezone"], row["first_day_of_week"]


def parse_goal_id(goal_id: str) -> int:
    """Clients send goal ids as strings (their models are typed that way).
    A non-numeric id can't exist, so treat it as a 404 rather than a 500."""
    try:
        return int(goal_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Goal not found")


async def upsert_snapshot(conn, goal_id: int, week_start, goal_row):
    """Refresh the frozen config on the current week's goal_weeks row."""
    await conn.execute(
        """
        INSERT INTO goal_weeks (goal_id, week_start, enabled, snapshot)
        VALUES ($1, $2, TRUE, $3)
        ON CONFLICT (goal_id, week_start) DO UPDATE SET snapshot = EXCLUDED.snapshot
        """,
        goal_id, to_date(week_start), snapshot_from_row(goal_row),
    )


@router.get("/", response_model=list[GoalOut])
async def get_goals():
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            week_start = get_week_start(get_today(tz), first_day)
            rows = await conn.fetch(
                f'SELECT {GOAL_COLS} FROM goals ORDER BY "order"'
            )
            entries = await conn.fetch(
                "SELECT goal_id, enabled FROM goal_weeks WHERE week_start = $1",
                to_date(week_start),
            )
        enabled_map = {e["goal_id"]: e["enabled"] for e in entries}
        return [goal_from_row(g, enabled_map.get(g["id"], True)) for g in rows]
    except Exception as e:
        logger.error(f"Failed to get goals: {e}")
        raise


@router.post("/", response_model=GoalChangedPayload)
async def create_goal(goal: GoalCreate, request: Request):
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            today = get_today(tz)
            week_start = get_week_start(today, first_day)
            week_end = get_week_end(week_start)

            # Reject a malformed header before opening a transaction.
            parse_client_seq(request)

            async with conn.transaction():
                # CAS inside the transaction: if anything below fails, the
                # sequence rolls back with it rather than stranding clients.
                seq = await consume_client_seq(conn, request)

                next_order = await conn.fetchval(
                    'SELECT COALESCE(MAX("order") + 1, 0) FROM goals'
                )
                try:
                    created = await conn.fetchrow(
                        f"""
                        INSERT INTO goals
                            (name, type, is_negative, times_per_week, times_per_day,
                             reward_rules, "order", version)
                        VALUES ($1, $2::goal_type, $3, $4, $5, $6, $7, 1)
                        RETURNING {GOAL_COLS}
                        """,
                        goal.name.strip(), goal.type.value, goal.is_negative,
                        goal.times_per_week, goal.times_per_day,
                        [r.model_dump() for r in goal.reward_rules], next_order,
                    )
                except asyncpg.UniqueViolationError:
                    # The unique index is the single source of truth here — the
                    # old read-then-check could let two concurrent creates race.
                    raise HTTPException(
                        status_code=422, detail="A goal with this name already exists"
                    )

                gid = created["id"]
                await upsert_snapshot(conn, gid, week_start, created)

                if goal.type == "daily":
                    await ensure_slots_for_goal(
                        conn, gid, week_start, today,
                        goal.times_per_day or 1, goal.is_negative,
                    )

            goal_week_row = await conn.fetchrow(
                "SELECT goal_id, week_start, enabled, snapshot FROM goal_weeks WHERE goal_id = $1 AND week_start = $2",
                gid, to_date(week_start),
            )
            goal_logs = await get_goal_week_logs(conn, gid, week_start, week_end)

        payload = GoalChangedPayload(
            action="created",
            goal=goal_from_row(created, enabled=True),
            goal_week=goal_week_from_row(goal_week_row),
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Created goal: {gid} name={goal.name}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to create goal: {e}")
        raise


@router.put("/{goal_id}", response_model=GoalChangedPayload)
async def update_goal(goal_id: str, goal: GoalUpdate, request: Request):
    gid = parse_goal_id(goal_id)
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            current = await conn.fetchrow(f"SELECT {GOAL_COLS} FROM goals WHERE id = $1", gid)
            if not current:
                raise HTTPException(status_code=404, detail="Goal not found")

            client_version = goal.version
            db_version = current["version"]
            effective_client_version = client_version if client_version is not None else db_version
            if db_version != effective_client_version:
                logger.warning(
                    f"Version conflict on goal {gid}: client={client_version} db={db_version}"
                )
                raise HTTPException(status_code=409, detail="Out of sync. Please reload.")

            parse_client_seq(request)

            data = goal.model_dump()
            update = {}
            for k, v in data.items():
                if k == "version":
                    continue
                if v is not None:
                    update[k] = v

            if not update:
                raise HTTPException(status_code=400, detail="No fields to update")

            if "name" in update:
                update["name"] = update["name"].strip()
            if "type" in update:
                update["type"] = update["type"].value if hasattr(update["type"], "value") else update["type"]
            if "reward_rules" in update:
                update["reward_rules"] = [
                    r.model_dump() if hasattr(r, "model_dump") else r
                    for r in update["reward_rules"]
                ]

            # Clear the irrelevant cadence field when the type changes, and make
            # sure the relevant one ends up set — goals_shape requires exactly
            # one, and a type switch that omits it would otherwise write a row
            # with neither. Fall back to the stored value, then to a sane
            # default (1 slot/day, 1 time/week).
            new_type = update.get("type") or current["type"]
            if new_type == "daily":
                update["times_per_week"] = None
                if update.get("times_per_day") is None:
                    update["times_per_day"] = current["times_per_day"] or 1
            elif new_type == "weekly_x":
                update["times_per_day"] = None
                if update.get("times_per_week") is None:
                    update["times_per_week"] = current["times_per_week"] or 1

            update["version"] = effective_client_version + 1

            casts = {"type": "::goal_type", "reward_rules": "::jsonb"}
            cols = list(update.keys())
            assignments = ", ".join(
                f'"{c}" = ${i + 1}{casts.get(c, "")}' for i, c in enumerate(cols)
            )
            values = [update[c] for c in cols]

            tz, first_day = await get_settings_cached(conn)
            today = get_today(tz)
            week_start = get_week_start(today, first_day)
            week_end = get_week_end(week_start)

            async with conn.transaction():
                seq = await consume_client_seq(conn, request)

                try:
                    # The version predicate makes check-and-write atomic. The
                    # read above can go stale between check and UPDATE, so
                    # without it two concurrent edits both pass validation and
                    # the second silently overwrites the first.
                    updated = await conn.fetchrow(
                        f"UPDATE goals SET {assignments} "
                        f"WHERE id = ${len(cols) + 1} AND version = ${len(cols) + 2} "
                        f"RETURNING {GOAL_COLS}",
                        *values, gid, effective_client_version,
                    )
                except asyncpg.UniqueViolationError:
                    raise HTTPException(
                        status_code=422, detail="A goal with this name already exists"
                    )

                if updated is None:
                    # Zero rows matched: another writer bumped version between
                    # our read and this UPDATE.
                    logger.warning(f"Lost update race on goal {gid}")
                    raise HTTPException(status_code=409, detail="Out of sync. Please reload.")

                await upsert_snapshot(conn, gid, week_start, updated)

                old_tpd = current["times_per_day"]
                old_type = current["type"]
                new_tpd = updated["times_per_day"]
                new_type = updated["type"]
                new_is_negative = updated["is_negative"]

                if new_type == "daily":
                    if old_type != "daily":
                        # Type changed to daily — reconcile handles existing slots.
                        await reconcile_slots_for_goal(
                            conn, gid, week_start, today, new_tpd or 1, new_is_negative
                        )
                    elif old_tpd != new_tpd and new_tpd:
                        await reconcile_slots_for_goal(
                            conn, gid, week_start, week_end, new_tpd, new_is_negative
                        )
                elif new_type == "weekly_x" and old_type == "daily":
                    # Collapse multi-slot days to a single slot. Negative goals
                    # count as kept only if every slot held; positive goals count
                    # as done if any slot was done.
                    # COALESCE guards the NULL case: ALL/ANY over an array
                    # containing NULL evaluates to NULL, which would write
                    # ARRAY[NULL] and break List[bool] on every later read.
                    await conn.execute(
                        """
                        UPDATE logs
                        SET slots = CASE WHEN $4
                                THEN ARRAY[COALESCE(true = ALL(slots), FALSE)]
                                ELSE ARRAY[COALESCE(true = ANY(slots), FALSE)] END
                        WHERE goal_id = $1 AND date BETWEEN $2 AND $3
                          AND cardinality(slots) > 1
                        """,
                        gid, to_date(week_start), to_date(week_end), new_is_negative,
                    )

            entry = await conn.fetchrow(
                "SELECT goal_id, week_start, enabled, snapshot FROM goal_weeks WHERE goal_id = $1 AND week_start = $2",
                gid, to_date(week_start),
            )
            enabled = entry["enabled"] if entry else True
            goal_logs = await get_goal_week_logs(conn, gid, week_start, week_end)

        payload = GoalChangedPayload(
            action="updated",
            goal=goal_from_row(updated, enabled),
            goal_week=goal_week_from_row(entry) if entry else None,
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Updated goal: {gid} version={update['version']}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update goal {gid}: {e}")
        raise


@router.delete("/{goal_id}", response_model=GoalChangedPayload)
async def delete_goal(goal_id: str, request: Request):
    gid = parse_goal_id(goal_id)
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            today = get_today(tz)
            week_start = get_week_start(today, first_day)
            week_end = get_week_end(week_start)

            parse_client_seq(request)

            async with conn.transaction():
                seq = await consume_client_seq(conn, request)

                # Hard delete the goal. Past weeks keep their goal_weeks rows
                # and logs — those render from the frozen snapshot, which is
                # why neither table has a cascading FK.
                await conn.execute("DELETE FROM goals WHERE id = $1", gid)
                await conn.execute(
                    "DELETE FROM goal_weeks WHERE goal_id = $1 AND week_start = $2",
                    gid, to_date(week_start),
                )
                await conn.execute(
                    "DELETE FROM logs WHERE goal_id = $1 AND date BETWEEN $2 AND $3",
                    gid, to_date(week_start), to_date(week_end),
                )

                # Close the gap in ordering. ROW_NUMBER assigns the compacted
                # positions; the WHERE skips rows already in place.
                reordered = await conn.fetch(
                    """
                    WITH ranked AS (
                        SELECT id, (ROW_NUMBER() OVER (ORDER BY "order") - 1)::int AS new_order
                        FROM goals
                    )
                    UPDATE goals g SET "order" = r.new_order
                    FROM ranked r
                    WHERE g.id = r.id AND g."order" <> r.new_order
                    RETURNING g.id, g."order" AS new_order
                    """
                )

                # Keep the current week's snapshots consistent with new ordering.
                for r in reordered:
                    await conn.execute(
                        """
                        UPDATE goal_weeks
                        SET snapshot = jsonb_set(snapshot, '{order}', to_jsonb($3::int))
                        WHERE goal_id = $1 AND week_start = $2
                        """,
                        r["id"], to_date(week_start), r["new_order"],
                    )

                # The payload must list every remaining goal's position, not
                # just the ones that moved.
                all_goals = await conn.fetch('SELECT id, "order" FROM goals ORDER BY "order"')

        reordered_goals = [
            {"goal_id": str(g["id"]), "new_order": g["order"]} for g in all_goals
        ]

        payload = GoalChangedPayload(
            action="deleted",
            goal_id=str(gid),
            week_start=week_start,
            seq=seq,
            reordered_goals=reordered_goals,
        )

        logger.info(f"Deleted goal: {gid}, reordered {len(reordered_goals)} remaining goals")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete goal {gid}: {e}")
        raise


@router.put("/reorder/batch")
async def reorder_goals(body: list[dict], request: Request):
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            week_start = get_week_start(get_today(tz), first_day)

            parse_client_seq(request)

            items = [(parse_goal_id(str(i["goal_id"])), int(i["new_order"])) for i in body]

            async with conn.transaction():
                seq = await consume_client_seq(conn, request)

                for gid, new_order in items:
                    await conn.execute(
                        'UPDATE goals SET "order" = $2 WHERE id = $1', gid, new_order
                    )
                    await conn.execute(
                        """
                        UPDATE goal_weeks
                        SET snapshot = jsonb_set(snapshot, '{order}', to_jsonb($3::int))
                        WHERE goal_id = $1 AND week_start = $2
                        """,
                        gid, to_date(week_start), new_order,
                    )

        payloads = [
            GoalChangedPayload(
                action="reordered",
                goal_id=str(gid),
                new_order=new_order,
                week_start=week_start,
                seq=seq,
            )
            for gid, new_order in items
        ]

        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", [p.model_dump() for p in payloads], exclude_session=session_id)
        logger.info(f"Reordered {len(payloads)} goals")
        return [p.model_dump() for p in payloads]
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to reorder goals: {e}")
        raise
