from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from contextlib import asynccontextmanager
import os
import logging
from dotenv import load_dotenv

load_dotenv()

# Configure logging from env
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("goals")

from .database import connect_db, close_db, get_db
from .routers import goals, logs, settings, weeks, events

async def ensure_indexes():
    db = get_db()
    await db.logs.create_index([("goal_id", 1), ("date", 1), ("slot", 1)], unique=True)
    await db.logs.create_index([("date", 1)])
    await db.goal_weeks.create_index([("week_start", 1), ("goal_id", 1)], unique=True)
    await db.goal_weeks.create_index([("week_start", 1), ("enabled", 1)])
    logger.info("Indexes ensured")

@asynccontextmanager
async def lifespan(app: FastAPI):
    app_env = os.getenv("APP_ENV", "PROD")
    logger.info(f"Starting Goals API — env={app_env} log_level={LOG_LEVEL}")
    await connect_db()
    await ensure_indexes()
    yield
    await close_db()
    logger.info("Goals API shutting down")

app = FastAPI(title="Goals API", lifespan=lifespan)

app.include_router(goals.router, prefix="/api/goals", tags=["goals"])
app.include_router(logs.router, prefix="/api/logs", tags=["logs"])
app.include_router(settings.router, prefix="/api/settings", tags=["settings"])
app.include_router(weeks.router, prefix="/api/weeks", tags=["weeks"])
app.include_router(events.router, prefix="/api/events", tags=["events"])

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
