from fastapi import APIRouter, Request
from bson import ObjectId
import logging
from ..database import get_db
from ..time_utils import get_today, get_week_start, get_week_end
from ..models import GoalChangedPayload, GoalWeekOut
from ..broadcaster import broadcast
from ..sequence import increment_sequence
from .goals import goal_from_doc, goal_week_from_doc, get_settings_cached, validate_client_seq
from .logs import get_goal_week_logs

router = APIRouter()
logger = logging.getLogger("goals.routers.weeks")


@router.post("/ensure")
async def ensure_week():
    try:
        db = get_db()
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)

        goals = await db.goals.find({}).to_list(None)
        existing = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        existing_ids = {e["goal_id"] for e in existing}

        new_entries = []
        for g in goals:
            gid = str(g["_id"])
            if gid not in existing_ids:
                new_entries.append({
                    "goal_id": gid,
                    "week_start": week_start,
                    "enabled": True,
                    "snapshot": {
                        "name": g.get("name"),
                        "order": g.get("order", 0),
                        "type": g.get("type"),
                        "is_negative": g.get("is_negative", False),
                        "times_per_day": g.get("times_per_day"),
                        "times_per_week": g.get("times_per_week"),
                        "reward_rules": g.get("reward_rules", []),
                    }
                })

        if new_entries:
            await db.goal_weeks.insert_many(new_entries)
            logger.info(f"Enrolled {len(new_entries)} goals for week {week_start}")

        deleted = await db.goal_weeks.delete_many({
            "week_start": {"$ne": week_start},
            "enabled": False,
        })
        if deleted.deleted_count:
            logger.info(f"Cleaned {deleted.deleted_count} disabled goal_weeks entries")

        return {"week_start": week_start, "enrolled": len(new_entries)}
    except Exception as e:
        logger.error(f"Failed to ensure week: {e}")
        raise


@router.put("/{goal_id}/enabled", response_model=GoalChangedPayload)
async def set_goal_enabled(goal_id: str, body: dict, request: Request):
    try:
        db = get_db()
        await validate_client_seq(request)
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        week_end = get_week_end(week_start)
        enabled = body.get("enabled", True)

        await db.goal_weeks.update_one(
            {"goal_id": goal_id, "week_start": week_start},
            {"$set": {"enabled": enabled}},
            upsert=True,
        )

        goal_doc = await db.goals.find_one({"_id": ObjectId(goal_id)})
        goal_week_doc = await db.goal_weeks.find_one({"goal_id": goal_id, "week_start": week_start})
        goal_logs = await get_goal_week_logs(db, goal_id, week_start, week_end)
        seq = await increment_sequence()

        payload = GoalChangedPayload(
            action="updated",
            goal=goal_from_doc(goal_doc, enabled),
            goal_week=goal_week_from_doc(goal_week_doc),
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Goal {goal_id} enabled={enabled} for week {week_start}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except Exception as e:
        logger.error(f"Failed to set goal enabled {goal_id}: {e}")
        raise
