package com.goals.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.data.models.CreateGoalRequest
import com.goals.app.data.models.Goal
import com.goals.app.data.models.GoalWeek
import com.goals.app.data.models.UpdateGoalRequest
import com.goals.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun GoalsScreen(
    goals: List<Goal>,
    goalWeeks: List<GoalWeek>,
    onAddGoal: (CreateGoalRequest) -> Unit,
    onUpdateGoal: (id: String, UpdateGoalRequest) -> Unit,
    onDeleteGoal: (id: String) -> Unit,
    onSetEnabled: (id: String, enabled: Boolean) -> Unit,
    onReorder: (orderedIds: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showForm by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }

    // Use SnapshotStateList so each swap is a fine-grained mutation, not a full list replace
    val orderedGoals = remember { mutableStateListOf(*goals.sortedBy { it.order }.toTypedArray()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(goals) {
        if (!isDragging) {
            orderedGoals.clear()
            orderedGoals.addAll(goals.sortedBy { it.order })
        }
    }

    val scope = rememberCoroutineScope()
    val reorderJob = remember { mutableListOf<Job>() }

    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        isDragging = true
        val fromIndex = orderedGoals.indexOfFirst { it.id == from.key }
        val toIndex = orderedGoals.indexOfFirst { it.id == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            orderedGoals.add(toIndex, orderedGoals.removeAt(fromIndex))
        }
        // Debounce: send to server 500ms after last move, then allow SSE sync again
        reorderJob.forEach { it.cancel() }
        reorderJob.clear()
        reorderJob.add(scope.launch {
            delay(500)
            onReorder(orderedGoals.map { it.id })
            isDragging = false
        })
    }

    if (showForm) {
        val editingGoalWeek = editingGoal?.let { g -> goalWeeks.find { it.goalId == g.id } }
        GoalFormSheet(
            editingGoal = editingGoal,
            editingGoalWeek = editingGoalWeek,
            onDismiss = { showForm = false; editingGoal = null },
            onSave = { data ->
                if (editingGoal != null) {
                    onUpdateGoal(editingGoal!!.id, UpdateGoalRequest(
                        name = data.name, type = data.type, isNegative = data.isNegative,
                        timesPerDay = if (data.type == "daily") data.timesPerDay else null,
                        timesPerWeek = if (data.type == "weekly_x") data.timesPerWeek else null,
                        rewardRules = data.rewardRules
                    ))
                } else {
                    onAddGoal(CreateGoalRequest(
                        name = data.name, type = data.type, isNegative = data.isNegative,
                        timesPerDay = if (data.type == "daily") data.timesPerDay else null,
                        timesPerWeek = if (data.type == "weekly_x") data.timesPerWeek else null,
                        rewardRules = data.rewardRules
                    ))
                }
                showForm = false; editingGoal = null
            },
            onDelete = editingGoal?.let { g -> { onDeleteGoal(g.id); showForm = false; editingGoal = null } },
            onSetEnabled = editingGoal?.let { g -> { enabled -> onSetEnabled(g.id, enabled) } }
        )
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        items(orderedGoals, key = { it.id.ifEmpty { it.name } }) { goal ->
            ReorderableItem(reorderState, key = goal.id.ifEmpty { goal.name }) { isDragging ->
                val goalWeek = goalWeeks.find { it.goalId == goal.id }
                val isEnabled = goalWeek?.enabled != false

                GoalCard(
                    goal = goal,
                    isEnabled = isEnabled,
                    isDragging = isDragging,
                    dragModifier = Modifier.draggableHandle(),
                    onClick = { editingGoal = goal; showForm = true },
                    modifier = Modifier.animateItem()
                )
            }
        }

        // Add button
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Border2Color, RoundedCornerShape(12.dp))
                    .clickable { editingGoal = null; showForm = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ NEW GOAL",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.08.sp,
                    color = Text3Color,
                    fontFamily = SyneFont
                )
            }
        }

        // Empty state
        if (orderedGoals.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No goals yet.\nTap + NEW GOAL to add one.",
                        color = Text3Color,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

@Composable
fun GoalCard(
    goal: Goal,
    isEnabled: Boolean,
    isDragging: Boolean,
    dragModifier: Modifier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metaText = when (goal.type) {
        "daily" -> "${goal.timesPerDay ?: 1}× / day"
        "weekly_x" -> "${goal.timesPerWeek ?: 3}× / week"
        else -> goal.type
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDragging) Surface2Color else SurfaceColor)
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .then(if (!isEnabled) Modifier else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle
        Box(
            modifier = dragModifier
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⠿",
                color = Text3Color,
                fontSize = 20.sp
            )
        }

        // Info
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = goal.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEnabled) TextColor else Text3Color,
                    fontFamily = SyneFont
                )
                if (goal.isNegative) {
                    Spacer(Modifier.width(6.dp))
                    Badge(text = "AVOID", color = RedColor, bgColor = RedDim)
                }
                if (!isEnabled) {
                    Spacer(Modifier.width(6.dp))
                    Badge(text = "PAUSED", color = Text3Color, bgColor = Border2Color)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = metaText,
                fontSize = 11.sp,
                color = Text3Color,
                fontFamily = DmMonoFont
            )
        }

        Spacer(Modifier.width(16.dp))
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, bgColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp,
            color = color,
            fontFamily = SyneFont
        )
    }
}
