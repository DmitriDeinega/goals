from fastapi import APIRouter, HTTPException
import logging
import os
import zoneinfo
from ..database import get_db
from ..rows import iso, to_date

VALID_FIRST_DAYS = {"sunday", "monday"}
VALID_CURRENCIES = {"NIS", "USD", "EUR", "GBP"}

router = APIRouter()
logger = logging.getLogger("goals.routers.settings")

# Columns a client is allowed to PUT. `timezone` and `first_day_of_week` are
# validated below; `currency` against VALID_CURRENCIES; `start_date` is a DATE.
ALLOWED = {"first_day_of_week", "start_date", "currency", "timezone"}


async def read_settings() -> dict:
    """Load the singleton settings row as a plain dict, with app_env attached.
    Shared with main.py's /api/init, which embeds the same payload."""
    pool = get_db()
    row = await pool.fetchrow(
        """
        SELECT timezone, first_day_of_week, currency, start_date
        FROM settings WHERE id = TRUE
        """
    )
    if not row:
        raise RuntimeError("Settings not found in DB")
    out = dict(row)
    out["start_date"] = iso(out["start_date"]) if out["start_date"] else None
    out["app_env"] = os.getenv("APP_ENV", "PROD")
    return out


@router.get("/")
async def get_settings():
    try:
        return await read_settings()
    except Exception as e:
        logger.error(f"Failed to get settings: {e}")
        raise


@router.put("/")
async def update_settings(data: dict):
    try:
        pool = get_db()
        update = {k: v for k, v in data.items() if k in ALLOWED}

        if "first_day_of_week" in update and update["first_day_of_week"] not in VALID_FIRST_DAYS:
            raise HTTPException(status_code=422, detail=f"first_day_of_week must be one of {VALID_FIRST_DAYS}")
        if "currency" in update and update["currency"] not in VALID_CURRENCIES:
            raise HTTPException(status_code=422, detail=f"currency must be one of {VALID_CURRENCIES}")
        if "timezone" in update:
            try:
                zoneinfo.ZoneInfo(update["timezone"])
            except (zoneinfo.ZoneInfoNotFoundError, KeyError):
                raise HTTPException(status_code=422, detail=f"Invalid timezone: {update['timezone']}")
        if update.get("start_date"):
            # Guard the type too — to_date passes non-str/non-date values
            # straight through, which would surface as a 500 from asyncpg.
            if not isinstance(update["start_date"], str):
                raise HTTPException(status_code=422, detail="start_date must be YYYY-MM-DD")
            try:
                update["start_date"] = to_date(update["start_date"])
            except ValueError:
                raise HTTPException(status_code=422, detail="start_date must be YYYY-MM-DD")

        if not update:
            return await read_settings()

        # Build a parameterized SET list. Column names come from ALLOWED, never
        # from raw client input, so they cannot be injected. first_day_of_week
        # needs an explicit ::first_day cast — asyncpg won't infer the enum
        # type from a plain text parameter.
        casts = {"first_day_of_week": "::first_day"}
        cols = list(update.keys())
        assignments = ", ".join(
            f"{c} = ${i + 1}{casts.get(c, '')}" for i, c in enumerate(cols)
        )
        sql = f"UPDATE settings SET {assignments} WHERE id = TRUE"
        values = [update[c] for c in cols]
        await pool.execute(sql, *values)

        logger.info(f"Settings updated: {cols}")
        return await read_settings()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update settings: {e}")
        raise
