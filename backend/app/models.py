from pydantic import BaseModel, model_validator
from typing import Optional, List
from enum import Enum

# ── CANONICAL VALIDATION RULES ────────────────────────────────────────────────
# This is the single source of truth. Keep frontend/GoalForm.jsx and
# android/.../GoalFormSheet.kt in sync when changing anything here.
#
#   times_per_week   : 1–7 (weekly_x goals only)
#   times_per_day    : >= 1 (daily goals only)
#   reward_rules     : min_completions >= 1, no duplicates,
#                      max = times_per_week (weekly) or 7 (daily)
#   reward_amount    : > 0
# ─────────────────────────────────────────────────────────────────────────────


class GoalType(str, Enum):
    daily = "daily"
    weekly_x = "weekly_x"


class RewardRule(BaseModel):
    min_completions: int
    reward_amount: float

    @model_validator(mode='after')
    def check_values(self):
        if self.min_completions < 1:
            raise ValueError('min_completions must be at least 1')
        if self.reward_amount <= 0:
            raise ValueError('reward_amount must be greater than 0')
        return self


def _validate_reward_rules(rules: List[RewardRule], max_completions: int):
    seen = set()
    for i, rule in enumerate(rules):
        if rule.min_completions > max_completions:
            raise ValueError(f'Rule {i+1}: completions cannot exceed {max_completions}')
        if rule.min_completions in seen:
            raise ValueError(f'Rule {i+1}: duplicate completions value {rule.min_completions}')
        seen.add(rule.min_completions)


class GoalCreate(BaseModel):
    name: str
    type: GoalType
    is_negative: bool = False
    times_per_week: Optional[int] = None
    times_per_day: Optional[int] = None
    reward_rules: List[RewardRule] = []
    order: int = 0

    @model_validator(mode='after')
    def check_fields(self):
        if not self.name.strip():
            raise ValueError('name is required')
        if self.type == GoalType.weekly_x:
            if self.times_per_week is None or self.times_per_week < 1:
                raise ValueError('times_per_week is required and must be at least 1 for weekly goals')
            if self.times_per_week > 7:
                raise ValueError('times_per_week cannot exceed 7')
            _validate_reward_rules(self.reward_rules, self.times_per_week)
        if self.type == GoalType.daily:
            if self.times_per_day is None or self.times_per_day < 1:
                raise ValueError('times_per_day is required and must be at least 1 for daily goals')
            _validate_reward_rules(self.reward_rules, 7)
        return self


class GoalUpdate(BaseModel):
    name: Optional[str] = None
    type: Optional[GoalType] = None
    is_negative: Optional[bool] = None
    times_per_week: Optional[int] = None
    times_per_day: Optional[int] = None
    reward_rules: Optional[List[RewardRule]] = None
    order: Optional[int] = None
    version: Optional[int] = None

    @model_validator(mode='after')
    def check_fields(self):
        if self.name is not None and not self.name.strip():
            raise ValueError('name cannot be empty')
        if self.type == GoalType.weekly_x:
            if self.times_per_week is not None and (self.times_per_week < 1 or self.times_per_week > 7):
                raise ValueError('times_per_week must be between 1 and 7')
            if self.reward_rules is not None:
                max_c = self.times_per_week or 7
                _validate_reward_rules(self.reward_rules, max_c)
        if self.type == GoalType.daily:
            if self.times_per_day is not None and self.times_per_day < 1:
                raise ValueError('times_per_day must be at least 1')
            if self.reward_rules is not None:
                _validate_reward_rules(self.reward_rules, 7)
        elif self.reward_rules is not None and self.type is None:
            # type unchanged — skip max check (only client knows current times_per_*), but still check duplicates
            _validate_reward_rules(self.reward_rules, max_completions=999)
        return self


class GoalOut(BaseModel):
    id: str
    name: str
    type: GoalType
    is_negative: bool
    times_per_week: Optional[int]
    times_per_day: Optional[int]
    reward_rules: List[RewardRule]
    order: int
    enabled: bool
    version: int = 0


class GoalWeekOut(BaseModel):
    goal_id: str
    week_start: str
    enabled: bool
    snapshot: dict


class LogCreate(BaseModel):
    goal_id: str
    date: str
    slot_index: int = 0
    value: bool


class LogOut(BaseModel):
    goal_id: str
    date: str
    slots: List[bool]


class InitResponse(BaseModel):
    goals: List[GoalOut]
    goal_weeks: List[GoalWeekOut]
    logs: List[LogOut]
    settings: dict
    seq: int


class LogChangedPayload(BaseModel):
    goal_id: str
    logs: List[LogOut]
    seq: int


class GoalChangedPayload(BaseModel):
    action: str
    goal: Optional[GoalOut] = None
    goal_id: Optional[str] = None
    goal_week: Optional[GoalWeekOut] = None
    logs: Optional[List[LogOut]] = None
    new_order: Optional[int] = None
    week_start: Optional[str] = None
    seq: int = 0
    reordered_goals: Optional[List[dict]] = None  # [{goal_id, new_order}] after delete
