package com.goals.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.data.models.Goal
import com.goals.app.data.models.GoalLog
import com.goals.app.data.models.GoalWeek
import com.goals.app.ui.theme.*
import com.goals.app.viewmodel.computeGoalStats
import com.goals.app.viewmodel.getDaysUpTo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun GoalRow(
    goal: Goal,
    goalWeek: GoalWeek?,
    log: GoalLog?,
    date: String,
    weekStart: String,
    weekEnd: String,
    today: String,
    weekLogs: List<GoalLog>,
    currency: String,
    onToggleSlot: (slotIndex: Int, currentValue: Boolean) -> Unit,
    inFlightToggles: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val snap = goalWeek?.snapshot
    val isNegative = snap?.isNegative ?: goal.isNegative
    val timesPerDay = snap?.timesPerDay ?: goal.timesPerDay ?: 1
    val type = snap?.type ?: goal.type

    val slotCount = if (type == "daily") timesPerDay else 1
    val slots = log?.slots ?: List(slotCount) { isNegative }  // defaults: neg=true, pos=false

    // Compute weekly stats for this goal
    val cutoff = if (weekEnd < today) weekEnd else today
    val weekDays = getDaysUpTo(weekStart, cutoff)
    val effectiveSnap = snap ?: com.goals.app.data.models.GoalWeekSnapshot(
        name = goal.name,
        type = goal.type,
        isNegative = goal.isNegative,
        timesPerDay = goal.timesPerDay,
        timesPerWeek = goal.timesPerWeek,
        rewardRules = goal.rewardRules,
        order = goal.order
    )
    val stats = computeGoalStats(effectiveSnap, goal.id, weekLogs, weekDays)
    val currencySymbol = if (currency == "NIS") "₪" else "$"

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Earned reward — leftmost (matches web: row-earned first)
            Text(
                text = if (stats.earnedReward > 0) {
                    val r = stats.earnedReward
                    val fmt = if (r == kotlin.math.floor(r)) "%.0f" else "%.2f"
                    "$currencySymbol${fmt.format(r)}"
                } else "",
                fontFamily = DmMonoFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentColor,
                modifier = Modifier.widthIn(min = 19.dp)
            )

            // Progress: completions/total
            Text(
                text = "${stats.completions}/${stats.totalSlots}",
                fontFamily = DmMonoFont,
                fontSize = 11.sp,
                color = Text3Color,
                modifier = Modifier.widthIn(min = 21.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Right
            )

            // Goal name + avoid badge
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snap?.name ?: goal.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextColor
                )
                if (isNegative) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(RedDim)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "avoid",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.sp,
                            color = RedColor,
                            fontFamily = SyneFont
                        )
                    }
                }
            }

            // Toggle buttons (one per slot)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slots.forEachIndexed { index, slotVal ->
                    val key = "${goal.id}|$date|$index"
                    ToggleButton(
                        slotValue = slotVal,
                        isNegative = isNegative,
                        isMultiSlot = slotCount > 1,
                        isToggling = key in inFlightToggles,
                        onClick = { onToggleSlot(index, slotVal) }
                    )
                }
            }
        }

        HorizontalDivider(color = BorderColor, thickness = 1.dp)
    }
}
