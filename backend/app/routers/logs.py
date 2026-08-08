from fastapi import APIRouter, HTTPException, Request
import logging
from ..database import get_db
from ..models import LogCreate, LogOut, LogChangedPayload
from ..time_utils import get_today, get_week_start, get_week_end
from ..broadcaster import broadcast
from ..sequence import consume_client_seq, parse_client_seq
from ..rows import log_from_row, to_date

router = APIRouter()
logger = logging.getLogger("goals.routers.logs")


async def get_settings(conn):
    row = await conn.fetchrow(
        "SELECT timezone, first_day_of_week FROM settings WHERE id = TRUE"
    )
    if not row:
        raise RuntimeError("Settings not found in DB")
    return row["timezone"], row["first_day_of_week"]


async def get_goal_week_logs(conn, goal_id: int, week_start, week_end) -> list[LogOut]:
    rows = await conn.fetch(
        """
        SELECT goal_id, date, slots FROM logs
        WHERE goal_id = $1 AND date BETWEEN $2 AND $3
        ORDER BY date
        """,
        goal_id, to_date(week_start), to_date(week_end),
    )
    return [log_from_row(r) for r in rows]


async def ensure_slots_for_goal(conn, goal_id: int, week_start, week_end,
                                times_per_day: int, is_negative: bool):
    """Create missing log rows across [week_start, week_end] with default
    slots. Replaces the old day-by-day Python loop with one set-based insert:
    generate_series produces the date range, ON CONFLICT DO NOTHING skips days
    that already have a row.

    default_value is is_negative — negative goals start "avoided" (true),
    positive goals start "not done" (false).
    """
    await conn.execute(
        """
        INSERT INTO logs (goal_id, date, slots)
        SELECT $1, d::date, array_fill($4::boolean, ARRAY[$5::int])
        FROM generate_series($2::date, $3::date, '1 day') AS d
        ON CONFLICT (goal_id, date) DO NOTHING
        """,
        goal_id, to_date(week_start), to_date(week_end), is_negative, times_per_day,
    )


async def reconcile_slots_for_goal(conn, goal_id: int, week_start, week_end,
                                   new_times_per_day: int, is_negative: bool):
    """Resize existing slot arrays to new_times_per_day across the range.

    Growing: append default-valued slots.
    Shrinking: drop unfulfilled (default-valued) slots first, preferring the
    ones nearest the end, and only truncate fulfilled slots if that isn't
    enough — this preserves the user's actual completions wherever possible.
    Mirrors the old Python logic, but per-row in SQL.
    """
    default_value = is_negative

    # Fill in any days that have no row at all.
    await ensure_slots_for_goal(conn, goal_id, week_start, week_end,
                                new_times_per_day, is_negative)

    rows = await conn.fetch(
        """
        SELECT date, slots FROM logs
        WHERE goal_id = $1 AND date BETWEEN $2 AND $3
          AND cardinality(slots) <> $4
        """,
        goal_id, to_date(week_start), to_date(week_end), new_times_per_day,
    )

    for row in rows:
        slots = list(row["slots"])
        count = len(slots)

        if new_times_per_day > count:
            slots = slots + [default_value] * (new_times_per_day - count)
        else:
            # Remove unfulfilled slots from the end first.
            while len(slots) > new_times_per_day:
                removed = False
                for i in range(len(slots) - 1, -1, -1):
                    if slots[i] == default_value:
                        slots.pop(i)
                        removed = True
                        break
                if not removed:
                    # All remaining slots are fulfilled — truncate.
                    slots = slots[:new_times_per_day]
                    break

        await conn.execute(
            "UPDATE logs SET slots = $3 WHERE goal_id = $1 AND date = $2",
            goal_id, row["date"], slots,
        )


@router.get("/", response_model=list[LogOut])
async def get_logs(date: str = None, week_start: str = None, week_end: str = None):
    try:
        pool = get_db()
        if date:
            rows = await pool.fetch(
                "SELECT goal_id, date, slots FROM logs WHERE date = $1",
                to_date(date),
            )
        elif week_start and week_end:
            rows = await pool.fetch(
                "SELECT goal_id, date, slots FROM logs WHERE date BETWEEN $1 AND $2",
                to_date(week_start), to_date(week_end),
            )
        else:
            rows = await pool.fetch("SELECT goal_id, date, slots FROM logs")
        return [log_from_row(r) for r in rows]
    except Exception as e:
        logger.error(f"Failed to get logs: {e}")
        raise


@router.post("/", response_model=LogChangedPayload)
async def upsert_log(log: LogCreate, request: Request):
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings(conn)
            today_str = get_today(tz)
            if log.date > today_str:
                raise HTTPException(status_code=400, detail="Cannot log a future date")

            try:
                goal_id = int(log.goal_id)
            except ValueError:
                raise HTTPException(status_code=404, detail="Goal not found")

            goal = await conn.fetchrow(
                "SELECT times_per_day, is_negative FROM goals WHERE id = $1", goal_id
            )
            if not goal:
                raise HTTPException(status_code=404, detail="Goal not found")

            max_slots = goal["times_per_day"] or 1
            if log.slot_index < 0 or log.slot_index >= max_slots:
                raise HTTPException(
                    status_code=400,
                    detail=f"slot_index must be between 0 and {max_slots - 1}",
                )

            # Reject a malformed header before opening a transaction.
            parse_client_seq(request)

            log_date = to_date(log.date)
            is_neg = goal["is_negative"]

            # Insert-with-defaults or update the single slot, in one statement.
            # Postgres arrays are 1-indexed, hence slot_index + 1.
            #
            # The stored array can be SHORTER than times_per_day (e.g. the goal
            # grew and this day fell outside the reconciled range). Assigning
            # past the end would pad the gap with NULLs, and LogOut.slots is
            # List[bool] — every later read of that week would then fail
            # validation. So pad up to the target length with the default value
            # first, then assign, all inside the one statement.
            default_slots = [is_neg] * max_slots
            default_slots[log.slot_index] = log.value
            async with conn.transaction():
                # CAS inside the transaction: a failure below rolls the
                # sequence back instead of stranding the client on a 409.
                seq = await consume_client_seq(conn, request)

                # Insert the row if absent; if present but too short, pad it out
                # to max_slots with the default value.
                await conn.execute(
                    """
                    INSERT INTO logs (goal_id, date, slots)
                    VALUES ($1, $2, $3)
                    ON CONFLICT (goal_id, date) DO UPDATE
                    SET slots = logs.slots || array_fill(
                            $4::boolean,
                            ARRAY[GREATEST($5 - cardinality(logs.slots), 0)])
                    """,
                    goal_id, log_date, default_slots, is_neg, max_slots,
                )
                # Now the array is guaranteed long enough to hold slot_index.
                await conn.execute(
                    "UPDATE logs SET slots[$3] = $4 WHERE goal_id = $1 AND date = $2",
                    goal_id, log_date, log.slot_index + 1, log.value,
                )

            week_start = get_week_start(log.date, first_day)
            week_end = get_week_end(week_start)
            goal_logs = await get_goal_week_logs(conn, goal_id, week_start, week_end)

        payload = LogChangedPayload(goal_id=str(goal_id), logs=goal_logs, seq=seq)

        session_id = request.headers.get("X-Session-ID")
        await broadcast("log_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to upsert log: {e}")
        raise
