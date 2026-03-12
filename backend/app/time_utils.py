from datetime import datetime, timedelta
import pytz


def get_today(timezone: str) -> str:
    """Return today's date string YYYY-MM-DD in the given timezone."""
    try:
        tz = pytz.timezone(timezone)
    except Exception:
        tz = pytz.UTC
    return datetime.now(tz).strftime("%Y-%m-%d")


def get_week_start(date_str: str, first_day: str = "sunday") -> str:
    """Return the week start date string for a given date."""
    from datetime import date
    d = date.fromisoformat(date_str)
    if first_day == "monday":
        dow = d.weekday()  # 0=Mon
        start = d - timedelta(days=dow)
    else:  # sunday
        dow = d.isoweekday() % 7  # 0=Sun
        start = d - timedelta(days=dow)
    return start.isoformat()


def get_week_end(week_start_str: str) -> str:
    from datetime import date
    start = date.fromisoformat(week_start_str)
    return (start + timedelta(days=6)).isoformat()
