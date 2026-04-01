from pydantic import BaseModel, model_validator
from typing import Optional, List
from enum import Enum


class GoalType(str, Enum):
    daily = "daily"
    weekly_x = "weekly_x"


class RewardRule(BaseModel):
    min_completions: int
    reward_amount: float


class GoalCreate(BaseModel):
    name: str
    type: GoalType
    is_negative: bool = False
    times_per_week: Optional[int] = None
    times_per_day: Optional[int] = None
    reward_rules: List[RewardRule] = []
    order: int = 0

    @model_validator(mode='after')
    def check_type_fields(self):
        if self.type == GoalType.weekly_x and (self.times_per_week is None or self.times_per_week < 1):
            raise ValueError('times_per_week is required and must be at least 1 for weekly goals')
        if self.type == GoalType.daily and (self.times_per_day is None or self.times_per_day < 1):
            raise ValueError('times_per_day is required and must be at least 1 for daily goals')
        return self


class GoalUpdate(BaseModel):
    name: Optional[str] = None
    type: Optional[GoalType] = None
    is_negative: Optional[bool] = None
    times_per_week: Optional[int] = None
    times_per_day: Optional[int] = None
    reward_rules: Optional[List[RewardRule]] = None
    order: Optional[int] = None
    active: Optional[bool] = None
    version: Optional[int] = None


class GoalOut(BaseModel):
    id: str
    name: str
    type: GoalType
    is_negative: bool
    times_per_week: Optional[int]
    times_per_day: Optional[int]
    reward_rules: List[RewardRule]
    order: int
    active: bool
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


class DayChangedPayload(BaseModel):
    date: str
    logs: List[LogOut]
    seq: int
