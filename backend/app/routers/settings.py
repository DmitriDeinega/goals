from fastapi import APIRouter, HTTPException
import logging
import os
import zoneinfo
from ..database import get_db

VALID_FIRST_DAYS = {"sunday", "monday"}
VALID_CURRENCIES = {"NIS", "USD", "EUR", "GBP"}

router = APIRouter()
logger = logging.getLogger("goals.routers.settings")


@router.get("/")
async def get_settings():
    try:
        db = get_db()
        doc = await db.settings.find_one({"_id": "global"})
        if not doc:
            raise RuntimeError("Settings not found in DB")
        doc.pop("_id", None)
        doc["app_env"] = os.getenv("APP_ENV", "PROD")
        return doc
    except Exception as e:
        logger.error(f"Failed to get settings: {e}")
        raise


@router.put("/")
async def update_settings(data: dict):
    try:
        db = get_db()
        allowed = {"first_day_of_week", "start_date", "currency", "timezone"}
        update = {k: v for k, v in data.items() if k in allowed}

        if "first_day_of_week" in update and update["first_day_of_week"] not in VALID_FIRST_DAYS:
            raise HTTPException(status_code=422, detail=f"first_day_of_week must be one of {VALID_FIRST_DAYS}")
        if "currency" in update and update["currency"] not in VALID_CURRENCIES:
            raise HTTPException(status_code=422, detail=f"currency must be one of {VALID_CURRENCIES}")
        if "timezone" in update:
            try:
                zoneinfo.ZoneInfo(update["timezone"])
            except (zoneinfo.ZoneInfoNotFoundError, KeyError):
                raise HTTPException(status_code=422, detail=f"Invalid timezone: {update['timezone']}")

        await db.settings.update_one(
            {"_id": "global"},
            {"$set": update},
            upsert=True,
        )
        logger.info(f"Settings updated: {list(update.keys())}")
        return await get_settings()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update settings: {e}")
        raise
