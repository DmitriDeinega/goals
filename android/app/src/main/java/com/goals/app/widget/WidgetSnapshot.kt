package com.goals.app.widget

import com.goals.app.data.models.AppSettings
import com.goals.app.data.models.Goal
import com.goals.app.data.models.GoalLog
import com.goals.app.data.models.GoalWeek

data class WidgetSnapshot(
    val goals: List<Goal> = emptyList(),
    val goalWeeks: List<GoalWeek> = emptyList(),
    val logs: List<GoalLog> = emptyList(),
    val today: String = "",
    val weekStart: String = "",
    val selectedDate: String = "",
    val settings: AppSettings? = null,
    val lastSeq: Long = 0L,
    val lastFcmAtMs: Long = 0L,
    /** In-flight pessimistic toggles. Key = "goalId|date|slotIndex".
     *  Stripped before persisting to disk via WidgetCache.persist(). */
    val inFlightToggles: Set<String> = emptySet(),
    val hydrated: Boolean = false
) {
    companion object {
        fun toggleKey(goalId: String, date: String, slotIndex: Int): String =
            "$goalId|$date|$slotIndex"
    }

    fun isToggling(goalId: String, date: String, slotIndex: Int): Boolean =
        toggleKey(goalId, date, slotIndex) in inFlightToggles
}
