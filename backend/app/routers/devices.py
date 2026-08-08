from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
import logging
from ..database import get_db

router = APIRouter()
logger = logging.getLogger("goals.routers.devices")

# Mongo expired device rows via a TTL index (60 days on last_seen_at).
# Postgres has no TTL, so day_watcher calls prune_stale_devices() once a day.
DEVICE_TTL_DAYS = 60


class DeviceRegister(BaseModel):
    token: str
    app_session_id: Optional[str] = None
    widget_session_id: Optional[str] = None
    platform: str = "android"


async def prune_stale_devices() -> int:
    """Delete device tokens not seen in DEVICE_TTL_DAYS. Replaces Mongo's TTL
    index. Called from day_watcher on each day rollover."""
    pool = get_db()
    result = await pool.execute(
        f"DELETE FROM devices WHERE last_seen_at < now() - INTERVAL '{DEVICE_TTL_DAYS} days'"
    )
    # asyncpg returns a status string like "DELETE 3"
    try:
        return int(result.split()[-1])
    except (ValueError, IndexError):
        return 0


@router.post("/register")
async def register_device(body: DeviceRegister):
    if not body.token:
        raise HTTPException(status_code=400, detail="token required")
    pool = get_db()
    await pool.execute(
        """
        INSERT INTO devices (token, app_session_id, widget_session_id, platform, last_seen_at)
        VALUES ($1, $2, $3, $4, now())
        ON CONFLICT (token) DO UPDATE SET
            app_session_id    = EXCLUDED.app_session_id,
            widget_session_id = EXCLUDED.widget_session_id,
            platform          = EXCLUDED.platform,
            last_seen_at      = now()
        """,
        body.token,
        body.app_session_id,
        body.widget_session_id,
        body.platform,
    )
    return {"status": "ok"}


@router.delete("/{token}")
async def unregister_device(token: str):
    pool = get_db()
    await pool.execute("DELETE FROM devices WHERE token = $1", token)
    return {"status": "ok"}
