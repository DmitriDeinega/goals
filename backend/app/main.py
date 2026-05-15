from fastapi import FastAPI, Request
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from contextlib import asynccontextmanager
import os
import logging
import asyncio
from dotenv import load_dotenv

load_dotenv()

LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("goals")

from .database import connect_db, close_db, get_db
from .routers import goals, logs, settings, weeks, events, devices
from .models import InitResponse, GoalWeekOut, LogOut
from .time_utils import get_today, get_week_start, get_week_end
from .routers.goals import goal_from_doc, get_settings_cached
from .day_watcher import watch_day
from .broadcaster import broadcast
from .sequence import get_sequence, increment_sequence
from .firebase_push import init_firebase, init_retry_loop, has_creds_env, is_enabled as fcm_is_enabled
from pydantic import BaseModel
from typing import List


class WeekDataResponse(BaseModel):
    week_start: str
    goal_weeks: List[GoalWeekOut]
    logs: List[LogOut]


async def ensure_indexes():
    db = get_db()
    await db.logs.create_index([("goal_id", 1), ("date", 1)], unique=True)
    await db.logs.create_index([("date", 1)])
    await db.goal_weeks.create_index([("week_start", 1), ("goal_id", 1)], unique=True)
    await db.goal_weeks.create_index([("week_start", 1), ("enabled", 1)])
    await db.devices.create_index([("token", 1)], unique=True)
    # TTL index: auto-delete device tokens that have not been seen for 60 days.
    await db.devices.create_index(
        [("last_seen_at", 1)], expireAfterSeconds=60 * 24 * 60 * 60
    )
    # Case-insensitive unique name. Backs up the application-level duplicate
    # check in create_goal/update_goal — without this index, two concurrent
    # creates can both pass the find_one check and both insert. Mongo's
    # `strength: 2` collation makes "Run" and "run" collide.
    await db.goals.create_index(
        [("name", 1)],
        unique=True,
        collation={"locale": "en", "strength": 2},
    )
    # Seed the global sequence doc so the atomic CAS in consume_client_seq
    # has a row to match against on the very first mutation. Idempotent.
    await db.sequence.update_one(
        {"_id": "global"},
        {"$setOnInsert": {"seq": 0}},
        upsert=True,
    )
    logger.info("Indexes ensured")


async def validate_settings():
    db = get_db()
    settings = await db.settings.find_one({"_id": "global"})
    if not settings:
        raise RuntimeError("Settings not found in DB. Please configure settings before starting.")
    required = ["timezone", "first_day_of_week", "currency"]
    missing = [k for k in required if not settings.get(k)]
    if missing:
        raise RuntimeError(f"Missing required settings: {missing}")
    logger.info(f"Settings validated: tz={settings['timezone']} first_day={settings['first_day_of_week']}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    app_env = os.getenv("APP_ENV", "PROD")
    logger.info(f"Starting Goals API — env={app_env} log_level={LOG_LEVEL}")
    await connect_db()
    await ensure_indexes()
    await validate_settings()
    initialized = init_firebase()
    # If init failed but creds env is set, schedule a background retry loop.
    if not initialized and has_creds_env():
        asyncio.create_task(init_retry_loop())
    asyncio.create_task(watch_day())
    yield
    await close_db()
    logger.info("Goals API shutting down")


app = FastAPI(title="Goals API", lifespan=lifespan)

app.include_router(goals.router, prefix="/api/goals", tags=["goals"])
app.include_router(logs.router, prefix="/api/logs", tags=["logs"])
app.include_router(settings.router, prefix="/api/settings", tags=["settings"])
app.include_router(weeks.router, prefix="/api/weeks", tags=["weeks"])
app.include_router(events.router, prefix="/api/events", tags=["events"])
app.include_router(devices.router, prefix="/api/devices", tags=["devices"])


@app.get("/api/health", tags=["health"])
async def health():
    return {"status": "ok"}


def log_from_doc(doc) -> LogOut:
    return LogOut(
        goal_id=doc["goal_id"],
        date=doc["date"],
        slots=doc["slots"],
    )


async def initialize_today_if_needed(db, today: str, week_start: str, first_day: str, session_id: str = None):
    from .routers.logs import ensure_slots_for_goal

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

    # Pre-populate today's slots for daily goals that don't have a log yet
    daily_goals = [g for g in all_goals if g.get("type") == "daily"]
    initialized_any = False
    for g in daily_goals:
        gid = str(g["_id"])
        existing = await db.logs.find_one({"goal_id": gid, "date": today})
        if not existing:
            await ensure_slots_for_goal(db, gid, today, today, g.get("times_per_day") or 1, g.get("is_negative", False))
            initialized_any = True

    if not initialized_any:
        return None

    log_docs = await db.logs.find({"date": today}).to_list(None)
    logs_data = [log_from_doc(doc).model_dump() for doc in log_docs]

    seq = await increment_sequence()
    await broadcast("day_changed", {"date": today, "logs": logs_data, "seq": seq}, exclude_session=session_id)
    logger.info(f"init: initialized new day {today} seq={seq}")
    return seq


@app.get("/api/init", response_model=InitResponse, tags=["init"])
async def init(request: Request):
    try:
        db = get_db()
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        week_end = get_week_end(week_start)

        session_id = request.headers.get("X-Session-ID")
        await initialize_today_if_needed(db, today, week_start, first_day, session_id)

        goal_docs = [doc async for doc in db.goals.find({}).sort("order", 1)]
        goal_week_docs = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        enabled_map = {e["goal_id"]: e.get("enabled", True) for e in goal_week_docs}
        goals_out = [goal_from_doc(g, enabled_map.get(str(g["_id"]), True)) for g in goal_docs]

        goal_weeks_out = [
            GoalWeekOut(
                goal_id=e["goal_id"],
                week_start=e["week_start"],
                enabled=e.get("enabled", True),
                snapshot=e.get("snapshot", {}),
            )
            for e in goal_week_docs
        ]

        log_docs = await db.logs.find({
            "date": {"$gte": week_start, "$lte": week_end}
        }).to_list(None)
        logs_out = [log_from_doc(doc) for doc in log_docs]

        settings_doc = await db.settings.find_one({"_id": "global"}) or {}
        settings_doc.pop("_id", None)
        settings_doc["app_env"] = os.getenv("APP_ENV", "PROD")

        seq = await get_sequence()

        return InitResponse(
            goals=goals_out,
            goal_weeks=goal_weeks_out,
            logs=logs_out,
            settings=settings_doc,
            seq=seq,
        )
    except Exception as e:
        logger.error(f"Failed to init: {e}")
        raise


@app.get("/api/week-data", response_model=WeekDataResponse, tags=["init"])
async def week_data(week_start: str):
    try:
        db = get_db()
        week_end = get_week_end(week_start)

        goal_week_docs = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        goal_weeks_out = [
            GoalWeekOut(
                goal_id=e["goal_id"],
                week_start=e["week_start"],
                enabled=e.get("enabled", True),
                snapshot=e.get("snapshot", {}),
            )
            for e in goal_week_docs
        ]

        log_docs = await db.logs.find({
            "date": {"$gte": week_start, "$lte": week_end}
        }).to_list(None)
        logs_out = [log_from_doc(doc) for doc in log_docs]

        return WeekDataResponse(
            week_start=week_start,
            goal_weeks=goal_weeks_out,
            logs=logs_out,
        )
    except Exception as e:
        logger.error(f"Failed to get week data: {e}")
        raise


# Serve React frontend
static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.exists(static_dir):
    app.mount("/assets", StaticFiles(directory=os.path.join(static_dir, "assets")), name="assets")

    @app.get("/{full_path:path}")
    async def serve_frontend(full_path: str):
        candidate = os.path.join(static_dir, full_path)
        if full_path and os.path.isfile(candidate):
            return FileResponse(candidate)
        return FileResponse(os.path.join(static_dir, "index.html"))
