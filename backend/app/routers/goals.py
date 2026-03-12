from fastapi import APIRouter, HTTPException
from bson import ObjectId
import logging
from ..database import get_db
from ..models import GoalCreate, GoalUpdate, GoalOut
from ..time_utils import get_today, get_week_start

router = APIRouter()
logger = logging.getLogger("goals.routers.goals")


async def get_settings_cached(db):
    s = await db.settings.find_one({"_id": "global"})
    s = s or {}
    return s.get("timezone", "Asia/Jerusalem"), s.get("first_day_of_week", "sunday")


def goal_from_doc(doc, enabled: bool = True) -> GoalOut:
    reward_rules = sorted(doc.get("reward_rules", []), key=lambda r: r.get("min_completions", 0))
    return GoalOut(
        id=str(doc["_id"]),
        name=doc["name"],
        type=doc["type"],
        is_negative=doc.get("is_negative", False),
        times_per_week=doc.get("times_per_week"),
        times_per_day=doc.get("times_per_day"),
        reward_rules=reward_rules,
        order=doc.get("order", 0),
        active=doc.get("active", True),
        enabled=enabled,
    )


@router.get("/", response_model=list[GoalOut])
async def get_goals():
    try:
        db = get_db()
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        goals = [doc async for doc in db.goals.find({"active": True}).sort("order", 1)]
        entries = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        enabled_map = {e["goal_id"]: e.get("enabled", True) for e in entries}
        return [goal_from_doc(g, enabled_map.get(str(g["_id"]), True)) for g in goals]
    except Exception as e:
        logger.error(f"Failed to get goals: {e}")
        raise


@router.post("/", response_model=GoalOut)
async def create_goal(goal: GoalCreate):
    try:
        db = get_db()
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        doc = goal.model_dump()
        doc["active"] = True
        result = await db.goals.insert_one(doc)
        gid = str(result.inserted_id)
        await db.goal_weeks.insert_one({"goal_id": gid, "week_start": week_start, "enabled": True})
        created = await db.goals.find_one({"_id": result.inserted_id})
        logger.info(f"Created goal: {gid} name={goal.name}")
        return goal_from_doc(created, enabled=True)
    except Exception as e:
        logger.error(f"Failed to create goal: {e}")
        raise


@router.put("/{goal_id}", response_model=GoalOut)
async def update_goal(goal_id: str, goal: GoalUpdate):
    try:
        db = get_db()
        update_data = {}
        for k, v in goal.model_dump().items():
            if k in ("reward_rules", "times_per_week", "times_per_day"):
                if v is not None:
                    update_data[k] = v
            elif v is not None:
                update_data[k] = v
        if not update_data:
            raise HTTPException(status_code=400, detail="No fields to update")
        await db.goals.update_one({"_id": ObjectId(goal_id)}, {"$set": update_data})
        updated = await db.goals.find_one({"_id": ObjectId(goal_id)})
        if not updated:
            raise HTTPException(status_code=404, detail="Goal not found")
        tz, first_day = await get_settings_cached(db)
        week_start = get_week_start(get_today(tz), first_day)
        entry = await db.goal_weeks.find_one({"goal_id": goal_id, "week_start": week_start})
        enabled = entry.get("enabled", True) if entry else True
        logger.info(f"Updated goal: {goal_id}")
        return goal_from_doc(updated, enabled)
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update goal {goal_id}: {e}")
        raise


@router.delete("/{goal_id}")
async def delete_goal(goal_id: str):
    try:
        db = get_db()
        await db.goals.update_one({"_id": ObjectId(goal_id)}, {"$set": {"active": False}})
        await db.goal_weeks.delete_many({"goal_id": goal_id})
        logger.info(f"Deleted goal: {goal_id}")
        return {"ok": True}
    except Exception as e:
        logger.error(f"Failed to delete goal {goal_id}: {e}")
        raise


@router.put("/reorder/batch")
async def reorder_goals(goal_ids: list[str]):
    try:
        db = get_db()
        for i, gid in enumerate(goal_ids):
            await db.goals.update_one({"_id": ObjectId(gid)}, {"$set": {"order": i}})
        return {"ok": True}
    except Exception as e:
        logger.error(f"Failed to reorder goals: {e}")
        raise
