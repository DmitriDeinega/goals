from pydantic import BaseModel
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


class GoalUpdate(BaseModel):
    name: Optional[str] = None
    type: Optional[GoalType] = None
    is_negative: Optional[bool] = None
    times_per_week: Optional[int] = None
    times_per_day: Optional[int] = None
    reward_rules: Optional[List[RewardRule]] = None
    order: Optional[int] = None
    active: Optional[bool] = None
    version: Optional[int] = None  # for optimistic locking


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
    version: int = 0  # increments on every save


class LogCreate(BaseModel):
    goal_id: str
    date: str
    slot: int = 0
    completed: bool


class LogOut(BaseModel):
    id: str
    goal_id: str
    date: str
    slot: int
    completed: bool
