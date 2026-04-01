from fastapi import APIRouter, HTTPException, Request
import logging
from ..database import get_db
from ..models import LogCreate, LogOut, LogChangedPayload
from ..time_utils import get_today, get_week_start, get_week_end
from ..broadcaster import broadcast
from ..sequence import increment_sequence, get_sequence

router = APIRouter()
logger = logging.getLogger("goals.routers.logs")


async def get_settings(db):
    s = await db.settings.find_one({"_id": "global"})
    if not s:
        raise RuntimeError("Settings not found in DB")
    return s["timezone"], s["first_day_of_week"]


def log_from_doc(doc) -> LogOut:
    return LogOut(
        goal_id=doc["goal_id"],
        date=doc["date"],
        slots=doc["slots"],
    )


async def get_goal_week_logs(db, goal_id: str, week_start: str, week_end: str) -> list[LogOut]:
    cursor = db.logs.find({
        "goal_id": goal_id,
        "date": {"$gte": week_start, "$lte": week_end}
    })
    return [log_from_doc(doc) async for doc in cursor]


async def ensure_slots_for_goal(db, goal_id: str, week_start: str, week_end: str, times_per_day: int, is_negative: bool):
    from datetime import date as Date, timedelta
    default_value = is_negative  # True for negative (avoided by default), False for positive

    current = Date.fromisoformat(week_start)
    end = Date.fromisoformat(week_end)

    while current <= end:
        date_str = current.isoformat()
        existing = await db.logs.find_one({"goal_id": goal_id, "date": date_str})
        if not existing:
            await db.logs.insert_one({
                "goal_id": goal_id,
                "date": date_str,
                "slots": [default_value] * times_per_day,
            })
        current += timedelta(days=1)


async def reconcile_slots_for_goal(db, goal_id: str, week_start: str, week_end: str, new_times_per_day: int, is_negative: bool):
    from datetime import date as Date, timedelta
    default_value = is_negative

    current = Date.fromisoformat(week_start)
    end = Date.fromisoformat(week_end)

    while current <= end:
        date_str = current.isoformat()
        doc = await db.logs.find_one({"goal_id": goal_id, "date": date_str})

        if not doc:
            # No log yet — create with new size
            await db.logs.insert_one({
                "goal_id": goal_id,
                "date": date_str,
                "slots": [default_value] * new_times_per_day,
            })
        else:
            current_slots = doc["slots"]
            current_count = len(current_slots)

            if new_times_per_day > current_count:
                # Add slots with default value
                new_slots = current_slots + [default_value] * (new_times_per_day - current_count)
                await db.logs.update_one(
                    {"goal_id": goal_id, "date": date_str},
                    {"$set": {"slots": new_slots}}
                )
            elif new_times_per_day < current_count:
                # Remove unfulfilled slots from the end first
                slots = current_slots[:]
                while len(slots) > new_times_per_day:
                    # Find last unfulfilled slot (default value) and remove it
                    # Search from end
                    removed = False
                    for i in range(len(slots) - 1, -1, -1):
                        if slots[i] == default_value:
                            slots.pop(i)
                            removed = True
                            break
                    if not removed:
                        # All slots are fulfilled — just truncate
                        slots = slots[:new_times_per_day]
                        break

                await db.logs.update_one(
                    {"goal_id": goal_id, "date": date_str},
                    {"$set": {"slots": slots}}
                )

        current += timedelta(days=1)


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


@router.post("/", response_model=LogChangedPayload)
async def upsert_log(log: LogCreate, request: Request):
    try:
        db = get_db()
        tz, first_day = await get_settings(db)
        today_str = get_today(tz)
        if log.date > today_str:
            raise HTTPException(status_code=400, detail="Cannot log a future date")

        # Validate client sequence
        client_seq = request.headers.get("X-Sequence")
        if client_seq is not None:
            current_seq = await get_sequence()
            if int(client_seq) != current_seq:
                raise HTTPException(status_code=409, detail="Out of sync. Please reload.")

        # Atomically update the specific slot
        await db.logs.update_one(
            {"goal_id": log.goal_id, "date": log.date},
            {"$set": {f"slots.{log.slot_index}": log.value}},
            upsert=False,  # slot doc must already exist
        )

        week_start = get_week_start(log.date, first_day)
        week_end = get_week_end(week_start)
        goal_logs = await get_goal_week_logs(db, log.goal_id, week_start, week_end)

        seq = await increment_sequence()
        payload = LogChangedPayload(goal_id=log.goal_id, logs=goal_logs, seq=seq)

        session_id = request.headers.get("X-Session-ID")
        await broadcast("log_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to upsert log: {e}")
        raise
