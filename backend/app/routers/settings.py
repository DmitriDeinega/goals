from fastapi import APIRouter
import logging
import os
from ..database import get_db

router = APIRouter()
logger = logging.getLogger("goals.routers.settings")

DEFAULTS = {
    "first_day_of_week": "sunday",
    "start_date": None,
    "currency": "NIS",
    "timezone": "Asia/Jerusalem",
}


@router.get("/")
async def get_settings():
    try:
        db = get_db()
        doc = await db.settings.find_one({"_id": "global"})
        if not doc:
            result = DEFAULTS.copy()
        else:
            doc.pop("_id", None)
            result = {**DEFAULTS, **doc}
        # Inject APP_ENV from server environment
        result["app_env"] = os.getenv("APP_ENV", "PROD")
        return result
    except Exception as e:
        logger.error(f"Failed to get settings: {e}")
        raise


@router.put("/")
async def update_settings(data: dict):
    try:
        db = get_db()
        allowed = {"first_day_of_week", "start_date", "currency", "timezone"}
        update = {k: v for k, v in data.items() if k in allowed}
        await db.settings.update_one(
            {"_id": "global"},
            {"$set": update},
            upsert=True,
        )
        logger.info(f"Settings updated: {list(update.keys())}")
        return await get_settings()
    except Exception as e:
        logger.error(f"Failed to update settings: {e}")
        raise
