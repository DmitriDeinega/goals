import asyncio
import logging
from .broadcaster import broadcast
from .database import get_db
from .time_utils import get_today, get_week_start
from .sequence import increment_sequence
from .rows import log_from_row, to_date

logger = logging.getLogger("goals.day_watcher")


async def _read_settings(conn):
    row = await conn.fetchrow(
        "SELECT timezone, first_day_of_week FROM settings WHERE id = TRUE"
    )
    if not row:
        raise RuntimeError("Settings not found in DB")
    return row["timezone"], row["first_day_of_week"]


async def watch_day():
    from .routers.weeks import enroll_goals_for_week
    from .routers.logs import ensure_slots_for_goal
    from .routers.devices import prune_stale_devices

    pool = get_db()
    async with pool.acquire() as conn:
        tz_name, _ = await _read_settings(conn)
    last_date = get_today(tz_name)

    while True:
        try:
            await asyncio.sleep(60)

            async with pool.acquire() as conn:
                tz_name, first_day = await _read_settings(conn)
                today = get_today(tz_name)

                if today == last_date:
                    continue

                logger.info(f"New day detected: {today}")

                # "Is the day initialized?" must mean EVERY daily goal has a
                # row — not "some row exists". reconcile_slots_for_goal
                # pre-creates rows through week_end, so a single goal resized
                # earlier in the week would otherwise make the watcher skip
                # enrollment, slot creation, and the broadcast for all the rest.
                missing = await conn.fetchval(
                    """
                    SELECT count(*) FROM goals g
                    WHERE g.type = 'daily'
                      AND NOT EXISTS (
                          SELECT 1 FROM logs l
                          WHERE l.goal_id = g.id AND l.date = $1
                      )
                    """,
                    to_date(today),
                )
                if not missing:
                    logger.info(f"{today} already initialized, skipping broadcast")
                    last_date = today
                    continue

                week_start = get_week_start(today, first_day)

                async with conn.transaction():
                    enrolled = await enroll_goals_for_week(conn, week_start)
                    if enrolled:
                        logger.info(f"Enrolled {enrolled} goals for week {week_start}")

                    daily_goals = await conn.fetch(
                        "SELECT id, times_per_day, is_negative FROM goals WHERE type = 'daily'"
                    )
                    for g in daily_goals:
                        await ensure_slots_for_goal(
                            conn, g["id"], today, today,
                            g["times_per_day"] or 1, g["is_negative"],
                        )

                rows = await conn.fetch(
                    "SELECT goal_id, date, slots FROM logs WHERE date = $1", to_date(today)
                )
                logs_data = [log_from_row(r).model_dump() for r in rows]

            # Only now that initialization has committed do we consider the day
            # handled. Advancing earlier would mean a transient DB error left
            # the day uninitialized until the process restarted.
            last_date = today

            seq = await increment_sequence()
            await broadcast("day_changed", {
                "date": today,
                "logs": logs_data,
                "seq": seq,
            })

            logger.info(f"day_changed broadcasted for {today} with {len(logs_data)} logs seq={seq}")

            # Replaces Mongo's TTL index on devices.last_seen_at. Runs once a
            # day on rollover, which is ample for a 60-day expiry.
            try:
                pruned = await prune_stale_devices()
                if pruned:
                    logger.info(f"Pruned {pruned} stale device tokens")
            except Exception as e:
                logger.warning(f"Device prune failed: {e}")

        except asyncio.CancelledError:
            logger.info("day_watcher cancelled")
            break
        except Exception as e:
            logger.exception(f"day_watcher error: {e}")
