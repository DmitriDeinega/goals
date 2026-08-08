"""Seed the singleton settings row.

The app refuses to boot without it — timezone and first_day_of_week drive
every date calculation. Run once against a fresh database:

    python backend/seed_settings.py --timezone Asia/Jerusalem --first-day sunday

Idempotent: re-running updates the existing row rather than failing.
"""

import argparse
import asyncio
import os
import sys
import zoneinfo

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.app.database import connect_db, close_db, get_db, init_schema

VALID_FIRST_DAYS = ("sunday", "monday")
VALID_CURRENCIES = ("NIS", "USD", "EUR", "GBP")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--timezone", default="Asia/Jerusalem")
    ap.add_argument("--first-day", default="sunday", choices=VALID_FIRST_DAYS)
    ap.add_argument("--currency", default="NIS", choices=VALID_CURRENCIES)
    ap.add_argument("--start-date", default=None, help="YYYY-MM-DD (optional)")
    args = ap.parse_args()

    try:
        zoneinfo.ZoneInfo(args.timezone)
    except Exception:
        sys.exit(f"Invalid timezone: {args.timezone}")

    start_date = None
    if args.start_date:
        from datetime import date
        start_date = date.fromisoformat(args.start_date)

    await connect_db()
    await init_schema()
    pool = get_db()
    await pool.execute(
        """
        INSERT INTO settings (id, timezone, first_day_of_week, currency, start_date)
        VALUES (TRUE, $1, $2::first_day, $3, $4)
        ON CONFLICT (id) DO UPDATE SET
            timezone          = EXCLUDED.timezone,
            first_day_of_week = EXCLUDED.first_day_of_week,
            currency          = EXCLUDED.currency,
            start_date        = EXCLUDED.start_date
        """,
        args.timezone, args.first_day, args.currency, start_date,
    )
    print(f"Settings seeded: tz={args.timezone} first_day={args.first_day} currency={args.currency}")
    await close_db()


if __name__ == "__main__":
    asyncio.run(main())
