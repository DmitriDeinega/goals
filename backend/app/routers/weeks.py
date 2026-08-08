from fastapi import APIRouter, HTTPException, Request
import logging
from ..database import get_db
from ..time_utils import get_today, get_week_start, get_week_end
from ..models import GoalChangedPayload
from ..broadcaster import broadcast
from ..sequence import consume_client_seq, parse_client_seq
from ..rows import goal_from_row, goal_week_from_row, to_date
from .goals import get_settings_cached, GOAL_COLS
from .logs import get_goal_week_logs

router = APIRouter()
logger = logging.getLogger("goals.routers.weeks")


async def enroll_goals_for_week(conn, week_start) -> int:
    """Create goal_weeks rows for any goal not yet enrolled in `week_start`,
    freezing each goal's current config into the snapshot. One set-based
    insert; ON CONFLICT skips goals already enrolled."""
    result = await conn.execute(
        """
        INSERT INTO goal_weeks (goal_id, week_start, enabled, snapshot)
        SELECT g.id, $1, TRUE, jsonb_build_object(
            'name', g.name,
            'order', g."order",
            'type', g.type,
            'is_negative', g.is_negative,
            'times_per_day', g.times_per_day,
            'times_per_week', g.times_per_week,
            'reward_rules', g.reward_rules
        )
        FROM goals g
        ON CONFLICT (goal_id, week_start) DO NOTHING
        """,
        to_date(week_start),
    )
    try:
        return int(result.split()[-1])
    except (ValueError, IndexError):
        return 0


@router.post("/ensure")
async def ensure_week():
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            week_start = get_week_start(get_today(tz), first_day)

            async with conn.transaction():
                enrolled = await enroll_goals_for_week(conn, week_start)

                # Disabled entries from past weeks carry no information —
                # the goal simply wasn't tracked then.
                cleaned_status = await conn.execute(
                    "DELETE FROM goal_weeks WHERE week_start <> $1 AND enabled = FALSE",
                    to_date(week_start),
                )
                try:
                    cleaned = int(cleaned_status.split()[-1])
                except (ValueError, IndexError):
                    cleaned = 0

        if enrolled:
            logger.info(f"Enrolled {enrolled} goals for week {week_start}")
        if cleaned:
            logger.info(f"Cleaned {cleaned} disabled goal_weeks entries")

        return {"week_start": week_start, "enrolled": enrolled}
    except Exception as e:
        logger.error(f"Failed to ensure week: {e}")
        raise


@router.put("/{goal_id}/enabled", response_model=GoalChangedPayload)
async def set_goal_enabled(goal_id: str, body: dict, request: Request):
    from .goals import parse_goal_id

    gid = parse_goal_id(goal_id)
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            today = get_today(tz)
            week_start = get_week_start(today, first_day)
            week_end = get_week_end(week_start)
            enabled = body.get("enabled", True)

            parse_client_seq(request)

            goal_row = await conn.fetchrow(f"SELECT {GOAL_COLS} FROM goals WHERE id = $1", gid)
            if not goal_row:
                # Without this the upsert below would INSERT a row with an empty
                # snapshot, resurrecting a nameless entry for a deleted goal.
                # Checked before the CAS so a 404 doesn't burn a sequence number.
                raise HTTPException(status_code=404, detail="Goal not found")

            # Upsert needs a snapshot for the insert path — a goal_weeks row
            # can be missing if the week was never ensured.
            from ..rows import snapshot_from_row

            async with conn.transaction():
                seq = await consume_client_seq(conn, request)

                await conn.execute(
                    """
                    INSERT INTO goal_weeks (goal_id, week_start, enabled, snapshot)
                    VALUES ($1, $2, $3, $4)
                    ON CONFLICT (goal_id, week_start) DO UPDATE SET enabled = EXCLUDED.enabled
                    """,
                    gid, to_date(week_start), enabled, snapshot_from_row(goal_row),
                )

            goal_week_row = await conn.fetchrow(
                "SELECT goal_id, week_start, enabled, snapshot FROM goal_weeks WHERE goal_id = $1 AND week_start = $2",
                gid, to_date(week_start),
            )
            goal_logs = await get_goal_week_logs(conn, gid, week_start, week_end)

        payload = GoalChangedPayload(
            action="updated",
            goal=goal_from_row(goal_row, enabled),
            goal_week=goal_week_from_row(goal_week_row),
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Goal {gid} enabled={enabled} for week {week_start}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to set goal enabled {gid}: {e}")
        raise
