package com.goals.app.data.models

import com.google.gson.annotations.SerializedName

// ── Domain Models ──────────────────────────────────────────────────────────────

data class RewardRule(
    @SerializedName("min_completions") val minCompletions: Int,
    @SerializedName("reward_amount") val rewardAmount: Double
)

data class Goal(
    val id: String,
    val name: String,
    val type: String,              // "daily" | "weekly_x"
    @SerializedName("is_negative") val isNegative: Boolean,
    @SerializedName("times_per_day") val timesPerDay: Int?,
    @SerializedName("times_per_week") val timesPerWeek: Int?,
    @SerializedName("reward_rules") val rewardRules: List<RewardRule>,
    val order: Int,
    val version: Int
)

data class GoalWeekSnapshot(
    val name: String,
    val type: String,
    @SerializedName("is_negative") val isNegative: Boolean,
    @SerializedName("times_per_day") val timesPerDay: Int?,
    @SerializedName("times_per_week") val timesPerWeek: Int?,
    @SerializedName("reward_rules") val rewardRules: List<RewardRule>,
    val order: Int
)

data class GoalWeek(
    @SerializedName("goal_id") val goalId: String,
    @SerializedName("week_start") val weekStart: String,
    val enabled: Boolean,
    val snapshot: GoalWeekSnapshot
)

data class GoalLog(
    @SerializedName("goal_id") val goalId: String,
    val date: String,
    val slots: List<Boolean>
)

data class AppSettings(
    val timezone: String,
    @SerializedName("first_day_of_week") val firstDayOfWeek: String,  // "sunday" | "monday"
    val currency: String,                                               // "NIS" | "USD"
    @SerializedName("start_date") val startDate: String?,
    @SerializedName("app_env") val appEnv: String?
)

// ── API Request/Response Models ────────────────────────────────────────────────

data class InitResponse(
    val goals: List<Goal>,
    @SerializedName("goal_weeks") val goalWeeks: List<GoalWeek>,
    val logs: List<GoalLog>,
    val settings: AppSettings,
    val seq: Long
)

data class WeekDataResponse(
    @SerializedName("week_start") val weekStart: String,
    @SerializedName("goal_weeks") val goalWeeks: List<GoalWeek>,
    val logs: List<GoalLog>
)

data class GoalChangedPayload(
    val action: String,
    val goal: Goal?,
    @SerializedName("goal_id") val goalId: String?,
    @SerializedName("goal_week") val goalWeek: GoalWeek?,
    val logs: List<GoalLog>?,
    @SerializedName("reordered_goals") val reorderedGoals: List<ReorderItem>?,
    val seq: Long
)

data class LogChangedPayload(
    @SerializedName("goal_id") val goalId: String,
    val logs: List<GoalLog>,
    val seq: Long
)

// ── API Request Bodies ─────────────────────────────────────────────────────────

data class CreateGoalRequest(
    val name: String,
    val type: String,
    @SerializedName("is_negative") val isNegative: Boolean,
    @SerializedName("times_per_day") val timesPerDay: Int?,
    @SerializedName("times_per_week") val timesPerWeek: Int?,
    @SerializedName("reward_rules") val rewardRules: List<RewardRule>
)

data class UpdateGoalRequest(
    val name: String,
    val type: String,
    @SerializedName("is_negative") val isNegative: Boolean,
    @SerializedName("times_per_day") val timesPerDay: Int?,
    @SerializedName("times_per_week") val timesPerWeek: Int?,
    @SerializedName("reward_rules") val rewardRules: List<RewardRule>,
    // Optimistic-locking handshake. Server compares this against goal.version;
    // mismatch returns 409 so a stale edit from another device can't clobber
    // a newer one. Web and Windows already send this — Android was the holdout.
    val version: Int
)

data class ToggleSlotRequest(
    @SerializedName("goal_id") val goalId: String,
    val date: String,
    @SerializedName("slot_index") val slotIndex: Int,
    val value: Boolean
)

data class ReorderItem(
    @SerializedName("goal_id") val goalId: String,
    @SerializedName("new_order") val newOrder: Int
)

data class SetEnabledRequest(
    val enabled: Boolean
)

// ── SSE Event Models ───────────────────────────────────────────────────────────

data class SseEvent(
    val type: String,   // "log_changed" | "goal_changed" | "day_changed" | "ping"
    val data: String    // raw JSON string, parsed per type
)

data class DayChangedPayload(
    val date: String,
    val logs: List<GoalLog>,
    val seq: Long
)

data class ReorderedPayload(
    val action: String,
    @SerializedName("goal_id") val goalId: String,
    @SerializedName("new_order") val newOrder: Int,
    val seq: Long
)
