from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
from datetime import datetime, timezone
import logging
from ..database import get_db

router = APIRouter()
logger = logging.getLogger("goals.routers.devices")


class DeviceRegister(BaseModel):
    token: str
    app_session_id: Optional[str] = None
    widget_session_id: Optional[str] = None
    platform: str = "android"


@router.post("/register")
async def register_device(body: DeviceRegister):
    if not body.token:
        raise HTTPException(status_code=400, detail="token required")
    db = get_db()
    now = datetime.now(timezone.utc)
    await db.devices.update_one(
        {"token": body.token},
        {
            "$set": {
                "token": body.token,
                "app_session_id": body.app_session_id,
                "widget_session_id": body.widget_session_id,
                "platform": body.platform,
                "last_seen_at": now,
            }
        },
        upsert=True,
    )
    return {"status": "ok"}


@router.delete("/{token}")
async def unregister_device(token: str):
    db = get_db()
    await db.devices.delete_one({"token": token})
    return {"status": "ok"}
