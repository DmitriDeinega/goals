"""Row → Pydantic mapping.

Centralizes the two conversions that Postgres forces on us and that the API
contract must hide:

  * ``date`` columns come back as ``datetime.date``; every client expects
    ISO ``YYYY-MM-DD`` strings.
  * ``id`` is a BIGINT internally but must serialize as a *string* — the
    Android (`val id: String`) and Windows (`string Id`) models are typed that
    way, and a bare JSON number would fail to deserialize on both.

asyncpg returns JSONB as a str unless a codec is registered; database.py sets
one up so ``reward_rules``/``snapshot`` arrive already parsed.
"""

from datetime import date as Date
from .models import GoalOut, GoalWeekOut, LogOut


def iso(d) -> str:
    """Normalize a DATE column (or an already-ISO string) to YYYY-MM-DD."""
    return d.isoformat() if isinstance(d, Date) else d


def to_date(s) -> Date:
    """Normalize an ISO string (or an already-parsed date) to a date."""
    return Date.fromisoformat(s) if isinstance(s, str) else s


def goal_from_row(row, enabled: bool = True) -> GoalOut:
    reward_rules = sorted(
        row["reward_rules"] or [],
        key=lambda r: r.get("min_completions", 0),
    )
    return GoalOut(
        id=str(row["id"]),
        name=row["name"],
        type=row["type"],
        is_negative=row["is_negative"],
        times_per_week=row["times_per_week"],
        times_per_day=row["times_per_day"],
        reward_rules=reward_rules,
        order=row["order"],
        enabled=enabled,
        version=row["version"],
    )


def goal_week_from_row(row) -> GoalWeekOut:
    return GoalWeekOut(
        goal_id=str(row["goal_id"]),
        week_start=iso(row["week_start"]),
        enabled=row["enabled"],
        snapshot=row["snapshot"] or {},
    )


def log_from_row(row) -> LogOut:
    return LogOut(
        goal_id=str(row["goal_id"]),
        date=iso(row["date"]),
        slots=list(row["slots"]),
    )


def snapshot_from_row(row) -> dict:
    """Freeze a goal's current config for goal_weeks.snapshot. Mirrors the
    shape the clients read out of past weeks — keep in sync with the readers
    in WidgetRenderer.kt and the frontend week views."""
    return {
        "name": row["name"],
        "order": row["order"],
        "type": row["type"],
        "is_negative": row["is_negative"],
        "times_per_day": row["times_per_day"],
        "times_per_week": row["times_per_week"],
        "reward_rules": row["reward_rules"] or [],
    }
