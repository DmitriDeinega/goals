package com.goals.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.data.models.AppSettings
import com.goals.app.data.models.Goal
import com.goals.app.data.models.GoalLog
import com.goals.app.data.models.GoalWeek
import com.goals.app.ui.components.*
import com.goals.app.ui.theme.*
import com.goals.app.viewmodel.WeekSummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    goals: List<Goal>,
    goalWeeks: List<GoalWeek>,
    logs: List<GoalLog>,
    selectedDate: String,
    weekStart: String,
    today: String,
    settings: AppSettings?,
    weekSummary: WeekSummary?,
    currency: String,
    /** True while this week's data is in flight. Distinguishes "still loading" from
     *  "loaded and genuinely empty", which otherwise both render as no rows. */
    weekLoading: Boolean = false,
    onDaySelected: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToggle: (goalId: String, date: String, slotIndex: Int, currentValue: Boolean) -> Unit,
    inFlightToggles: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val weekEnd = LocalDate.parse(weekStart, fmt).plusDays(6).format(fmt)

    // Build display list: enabled goal weeks sorted by snapshot order
    // Show even if goal not found in goals list (uses snapshot data)
    data class GoalDisplay(val goal: Goal?, val goalWeek: GoalWeek)
    val displayGoals = goalWeeks
        .filter { it.enabled }
        .sortedBy { it.snapshot.order }
        .map { gw -> GoalDisplay(goals.find { it.id == gw.goalId }, gw) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // Week strip
        item {
            Spacer(Modifier.height(12.dp))
            WeekStrip(
                selectedDate = selectedDate,
                today = today,
                startDate = settings?.startDate,
                firstDay = settings?.firstDayOfWeek ?: "sunday",
                onDaySelected = onDaySelected,
                onPrevWeek = onPrevWeek,
                onNextWeek = onNextWeek
            )
            Spacer(Modifier.height(16.dp))
        }

        // Week summary
        item {
            WeekSummarySection(
                summary = weekSummary,
                currency = currency
            )
            Spacer(Modifier.height(8.dp))
        }

        // Goal rows, loading state, or empty state
        if (weekLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = AccentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else if (displayGoals.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No goals for this week.\nAdd goals in the Goals tab.",
                        color = Text3Color,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        } else {
            items(displayGoals, key = { it.goalWeek.goalId }) { (goal, goalWeek) ->
                val log = logs.find { it.goalId == goalWeek.goalId && it.date == selectedDate }
                // Use goal if available, otherwise build a minimal one from snapshot
                val effectiveGoal = goal ?: Goal(
                    id = goalWeek.goalId,
                    name = goalWeek.snapshot.name,
                    type = goalWeek.snapshot.type ?: "daily",
                    isNegative = goalWeek.snapshot.isNegative,
                    timesPerDay = goalWeek.snapshot.timesPerDay,
                    timesPerWeek = goalWeek.snapshot.timesPerWeek,
                    rewardRules = goalWeek.snapshot.rewardRules,
                    order = goalWeek.snapshot.order,
                    version = 0
                )
                GoalRow(
                    inFlightToggles = inFlightToggles,
                    goal = effectiveGoal,
                    goalWeek = goalWeek,
                    log = log,
                    date = selectedDate,
                    weekStart = weekStart,
                    weekEnd = weekEnd,
                    today = today,
                    weekLogs = logs,
                    currency = currency,
                    onToggleSlot = { slotIndex, currentValue ->
                        onToggle(goalWeek.goalId, selectedDate, slotIndex, currentValue)
                    }
                )
            }
        }
    }
}
