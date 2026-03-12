from fastapi import APIRouter, HTTPException
import logging
from ..database import get_db
from ..models import LogCreate, LogOut
from ..time_utils import get_today

from ..broadcaster import broadcast

router = APIRouter()
logger = logging.getLogger("goals.routers.logs")


async def get_tz(db) -> str:
    s = await db.settings.find_one({"_id": "global"})
    return (s or {}).get("timezone", "Asia/Jerusalem")


def log_from_doc(doc) -> LogOut:
    return LogOut(
        id=str(doc["_id"]),
        goal_id=doc["goal_id"],
        date=doc["date"],
        slot=doc.get("slot", 0),
        completed=doc["completed"],
    )


@router.get("/", response_model=list[LogOut])
async def get_logs(date: str = None, week_start: str = None, week_end: str = None):
    try:
        db = get_db()
        query = {}
        if date:
            query["date"] = date
        elif week_start and week_end:
            query["date"] = {"$gte": week_start, "$lte": week_end}
        cursor = db.logs.find(query)
        return [log_from_doc(doc) async for doc in cursor]
    except Exception as e:
        logger.error(f"Failed to get logs: {e}")
        raise


@router.post("/", response_model=LogOut)
async def upsert_log(log: LogCreate):
    try:
        db = get_db()
        tz = await get_tz(db)
        today_str = get_today(tz)
        if log.date > today_str:
            raise HTTPException(status_code=400, detail="Cannot log a future date")
        filter_q = {"goal_id": log.goal_id, "date": log.date, "slot": log.slot}
        update = {"$set": {"completed": log.completed}}
        result = await db.logs.find_one_and_update(
            filter_q, update, upsert=True, return_document=True,
        )
        if not result:
            result = await db.logs.find_one(filter_q)
        await broadcast("logs_changed")
        return log_from_doc(result)
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to upsert log: {e}")
        raise


@router.get("/week-summary")
async def week_summary(week_start: str, week_end: str, selected_date: str = None):
    from datetime import date as Date, timedelta
    try:
        db = get_db()
        tz = await get_tz(db)
        today_str = get_today(tz)
        cutoff = min(selected_date, today_str) if selected_date else today_str

        gw_entries = await db.goal_weeks.find(
            {"week_start": week_start, "enabled": True}
        ).to_list(None)
        enabled_ids = {e["goal_id"] for e in gw_entries}

        if not enabled_ids:
            return {"week_start": week_start, "week_end": week_end, "goals": [], "total_earned": 0}

        logs = await db.logs.find({"date": {"$gte": week_start, "$lte": week_end}}).to_list(None)
        goals = await db.goals.find({"active": True}).to_list(None)
        goals = [g for g in goals if str(g["_id"]) in enabled_ids]

        def days_up_to_cutoff():
            current = Date.fromisoformat(week_start)
            end = Date.fromisoformat(min(week_end, cutoff))
            days = []
            while current <= end:
                days.append(current.isoformat())
                current += timedelta(days=1)
            return days

        week_days = days_up_to_cutoff()
        summary = []

        for goal in goals:
            gid = str(goal["_id"])
            goal_logs = [l for l in logs if l["goal_id"] == gid]
            is_negative = goal.get("is_negative", False)

            if goal["type"] == "daily":
                tpd = goal.get("times_per_day") or 1
                if tpd > 1:
                    if is_negative:
                        failed_days = set()
                        for d in week_days:
                            if any(not l["completed"] for l in goal_logs if l["date"] == d):
                                failed_days.add(d)
                        completions = len(week_days) - len(failed_days)
                    else:
                        completions = sum(
                            1 for d in week_days
                            if sum(1 for l in goal_logs if l["date"] == d and l["completed"]) >= tpd
                        )
                else:
                    if is_negative:
                        failed = {l["date"] for l in goal_logs if not l["completed"]}
                        completions = len(week_days) - len(failed & set(week_days))
                    else:
                        completions = sum(1 for l in goal_logs if l["completed"] and l["date"] in week_days)
                total_slots = 7
            else:
                tpw = goal.get("times_per_week", 7)
                if is_negative:
                    failed = {l["date"] for l in goal_logs if not l["completed"]}
                    completions = len(week_days) - len(failed & set(week_days))
                else:
                    raw = sum(1 for l in goal_logs if l["completed"] and l["date"] in week_days)
                    completions = min(raw, tpw)
                total_slots = tpw

            rules = sorted(goal.get("reward_rules", []), key=lambda r: r["min_completions"])
            earned = sum(r["reward_amount"] for r in rules if completions >= r["min_completions"])

            summary.append({
                "goal_id": gid,
                "goal_name": goal["name"],
                "completions": completions,
                "total_slots": total_slots,
                "earned_reward": earned,
            })

        return {
            "week_start": week_start,
            "week_end": week_end,
            "goals": summary,
            "total_earned": sum(g["earned_reward"] for g in summary),
        }
    except Exception as e:
        logger.error(f"Failed to get week summary: {e}")
        raise
