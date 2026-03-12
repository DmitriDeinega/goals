from fastapi import APIRouter
from bson import ObjectId
import logging
from ..database import get_db
from ..time_utils import get_today, get_week_start

from ..broadcaster import broadcast

router = APIRouter()
logger = logging.getLogger("goals.routers.weeks")


async def get_tz() -> str:
    db = get_db()
    settings = await db.settings.find_one({"_id": "global"})
    return (settings or {}).get("timezone", "Asia/Jerusalem")


async def get_first_day() -> str:
    db = get_db()
    settings = await db.settings.find_one({"_id": "global"})
    return (settings or {}).get("first_day_of_week", "sunday")


@router.post("/ensure")
async def ensure_week():
    try:
        db = get_db()
        tz = await get_tz()
        first_day = await get_first_day()
        today = get_today(tz)
        week_start = get_week_start(today, first_day)

        goals = await db.goals.find({"active": True}).to_list(None)
        goal_ids = [str(g["_id"]) for g in goals]

        existing = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        existing_ids = {e["goal_id"] for e in existing}

        new_entries = [
            {"goal_id": gid, "week_start": week_start, "enabled": True}
            for gid in goal_ids if gid not in existing_ids
        ]
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


@router.put("/{goal_id}/enabled")
async def set_goal_enabled(goal_id: str, body: dict):
    try:
        db = get_db()
        tz = await get_tz()
        first_day = await get_first_day()
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        enabled = body.get("enabled", True)

        await db.goal_weeks.update_one(
            {"goal_id": goal_id, "week_start": week_start},
            {"$set": {"enabled": enabled}},
            upsert=True,
        )
        logger.info(f"Goal {goal_id} enabled={enabled} for week {week_start}")
        await broadcast("goals_changed")
        return {"goal_id": goal_id, "week_start": week_start, "enabled": enabled}
    except Exception as e:
        logger.error(f"Failed to set goal enabled {goal_id}: {e}")
        raise
