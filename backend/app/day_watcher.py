import asyncio
import logging
from .broadcaster import broadcast
from .database import get_db
from .time_utils import get_today, get_week_start
from .sequence import increment_sequence

logger = logging.getLogger("goals.day_watcher")


async def watch_day():
    db = get_db()
    settings = await db.settings.find_one({"_id": "global"})
    if not settings:
        raise RuntimeError("Settings not found in DB")

    tz_name = settings["timezone"]
    last_date = get_today(tz_name)

    while True:
        try:
            await asyncio.sleep(60)

            settings = await db.settings.find_one({"_id": "global"})
            if not settings:
                raise RuntimeError("Settings not found in DB")

            tz_name = settings["timezone"]
            first_day = settings["first_day_of_week"]
            today = get_today(tz_name)

            if today == last_date:
                continue

            last_date = today
            logger.info(f"New day detected: {today}")

            existing = await db.logs.find_one({"date": today})
            if existing:
                logger.info(f"{today} already initialized, skipping broadcast")
                continue

            week_start = get_week_start(today, first_day)

            all_goals = await db.goals.find({}).to_list(None)
            existing_gw = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
            existing_ids = {e["goal_id"] for e in existing_gw}

            for g in all_goals:
                gid = str(g["_id"])
                if gid not in existing_ids:
                    await db.goal_weeks.insert_one({
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
                    logger.info(f"Enrolled goal {gid} for week {week_start}")

            from .routers.logs import ensure_slots_for_goal, log_from_doc

            daily_goals = [g for g in all_goals if g.get("type") == "daily"]
            for g in daily_goals:
                gid = str(g["_id"])
                tpd = g.get("times_per_day") or 1
                is_neg = g.get("is_negative", False)
                await ensure_slots_for_goal(db, gid, today, today, tpd, is_neg)

            log_docs = await db.logs.find({"date": today}).to_list(None)
            logs_data = [log_from_doc(doc).model_dump() for doc in log_docs]

            seq = await increment_sequence()
            await broadcast("day_changed", {
                "date": today,
                "logs": logs_data,
                "seq": seq,
            })

            logger.info(f"day_changed broadcasted for {today} with {len(logs_data)} logs seq={seq}")

        except asyncio.CancelledError:
            logger.info("day_watcher cancelled")
            break
        except Exception as e:
            logger.exception(f"day_watcher error: {e}")
