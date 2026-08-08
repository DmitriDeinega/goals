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

from .database import connect_db, close_db, get_db, init_schema
from .routers import goals, logs, settings, weeks, events, devices
from .models import InitResponse, GoalWeekOut, LogOut
from .time_utils import get_today, get_week_start, get_week_end
from .routers.goals import get_settings_cached, GOAL_COLS
from .routers.weeks import enroll_goals_for_week
from .routers.settings import read_settings
from .rows import goal_from_row, goal_week_from_row, log_from_row, to_date
from .day_watcher import watch_day
from .broadcaster import broadcast
from .sequence import get_sequence, increment_sequence
from .firebase_push import init_firebase, init_retry_loop, has_creds_env
from pydantic import BaseModel
from typing import List


class WeekDataResponse(BaseModel):
    week_start: str
    goal_weeks: List[GoalWeekOut]
    logs: List[LogOut]


async def validate_settings():
    """The settings row drives timezone and week boundaries app-wide; without
    it nothing can compute 'today', so fail fast at boot rather than 500 on
    the first request. Schema CHECKs guarantee the columns are non-null, so
    only presence of the row needs checking here."""
    pool = get_db()
    row = await pool.fetchrow(
        "SELECT timezone, first_day_of_week FROM settings WHERE id = TRUE"
    )
    if not row:
        raise RuntimeError(
            "Settings not found in DB. Seed the settings row before starting "
            "(see backend/seed_settings.py)."
        )
    logger.info(
        f"Settings validated: tz={row['timezone']} first_day={row['first_day_of_week']}"
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    app_env = os.getenv("APP_ENV", "PROD")
    logger.info(f"Starting Goals API — env={app_env} log_level={LOG_LEVEL}")
    await connect_db()
    await init_schema()
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


async def initialize_today_if_needed(conn, today: str, week_start: str, session_id: str = None):
    """Enroll goals in the current week and pre-populate today's slots for
    daily goals. Broadcasts day_changed only if something was actually
    created, so a plain refresh doesn't churn the sequence."""
    from .routers.logs import ensure_slots_for_goal

    await enroll_goals_for_week(conn, week_start)

    daily_goals = await conn.fetch(
        "SELECT id, times_per_day, is_negative FROM goals WHERE type = 'daily'"
    )

    initialized_any = False
    for g in daily_goals:
        exists = await conn.fetchval(
            "SELECT 1 FROM logs WHERE goal_id = $1 AND date = $2",
            g["id"], to_date(today),
        )
        if not exists:
            await ensure_slots_for_goal(
                conn, g["id"], today, today,
                g["times_per_day"] or 1, g["is_negative"],
            )
            initialized_any = True

    if not initialized_any:
        return None

    rows = await conn.fetch(
        "SELECT goal_id, date, slots FROM logs WHERE date = $1", to_date(today)
    )
    logs_data = [log_from_row(r).model_dump() for r in rows]

    seq = await increment_sequence()
    await broadcast("day_changed", {"date": today, "logs": logs_data, "seq": seq}, exclude_session=session_id)
    logger.info(f"init: initialized new day {today} seq={seq}")
    return seq


@app.get("/api/init", response_model=InitResponse, tags=["init"])
async def init(request: Request):
    try:
        pool = get_db()
        async with pool.acquire() as conn:
            tz, first_day = await get_settings_cached(conn)
            today = get_today(tz)
            week_start = get_week_start(today, first_day)
            week_end = get_week_end(week_start)

            session_id = request.headers.get("X-Session-ID")
            await initialize_today_if_needed(conn, today, week_start, session_id)

            goal_rows = await conn.fetch(f'SELECT {GOAL_COLS} FROM goals ORDER BY "order"')
            gw_rows = await conn.fetch(
                "SELECT goal_id, week_start, enabled, snapshot FROM goal_weeks WHERE week_start = $1",
                to_date(week_start),
            )
            log_rows = await conn.fetch(
                "SELECT goal_id, date, slots FROM logs WHERE date BETWEEN $1 AND $2",
                to_date(week_start), to_date(week_end),
            )
            settings_doc = await read_settings()
            seq = await get_sequence()

        enabled_map = {e["goal_id"]: e["enabled"] for e in gw_rows}

        return InitResponse(
            goals=[goal_from_row(g, enabled_map.get(g["id"], True)) for g in goal_rows],
            goal_weeks=[goal_week_from_row(e) for e in gw_rows],
            logs=[log_from_row(r) for r in log_rows],
            settings=settings_doc,
            seq=seq,
        )
    except Exception as e:
        logger.error(f"Failed to init: {e}")
        raise


@app.get("/api/week-data", response_model=WeekDataResponse, tags=["init"])
async def week_data(week_start: str):
    try:
        pool = get_db()
        week_end = get_week_end(week_start)

        async with pool.acquire() as conn:
            gw_rows = await conn.fetch(
                "SELECT goal_id, week_start, enabled, snapshot FROM goal_weeks WHERE week_start = $1",
                to_date(week_start),
            )
            log_rows = await conn.fetch(
                "SELECT goal_id, date, slots FROM logs WHERE date BETWEEN $1 AND $2",
                to_date(week_start), to_date(week_end),
            )

        return WeekDataResponse(
            week_start=week_start,
            goal_weeks=[goal_week_from_row(e) for e in gw_rows],
            logs=[log_from_row(r) for r in log_rows],
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
