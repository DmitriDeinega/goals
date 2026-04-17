package com.goals.app.ui.goals

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.data.models.*
import com.goals.app.ui.theme.*

private fun validateForm(
    name: String,
    type: String,
    timesPerDay: String,
    timesPerWeek: String,
    rewardRules: List<Pair<String, String>>,
    maxCompletions: Int
): String? {
    if (name.isBlank()) return "Name is required"
    if (type == "daily") {
        val v = timesPerDay.toIntOrNull()
        if (v == null || v < 1) return "Times per day must be at least 1"
    } else {
        val v = timesPerWeek.toIntOrNull()
        if (v == null || v < 1) return "Times per week must be at least 1"
        if (v > 7) return "Times per week cannot exceed 7"
    }
    val seenCompletions = mutableSetOf<Int>()
    rewardRules.forEachIndexed { i, (minComp, reward) ->
        val mc = minComp.toIntOrNull()
        if (mc == null || mc < 1) return "Rule ${i + 1}: completions must be at least 1"
        if (mc > maxCompletions) return "Rule ${i + 1}: completions can't exceed $maxCompletions"
        if (!seenCompletions.add(mc)) return "Rule ${i + 1}: duplicate completions value $mc"
        val ra = reward.toDoubleOrNull()
        if (ra == null || ra <= 0) return "Rule ${i + 1}: reward must be greater than 0"
    }
    return null
}

data class GoalFormData(
    val name: String = "",
    val type: String = "daily",
    val isNegative: Boolean = false,
    val timesPerDay: Int = 1,
    val timesPerWeek: Int = 3,
    val rewardRules: List<RewardRule> = emptyList(),
    val enabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormSheet(
    editingGoal: Goal? = null,
    editingGoalWeek: GoalWeek? = null,
    onDismiss: () -> Unit,
    onSave: (GoalFormData) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSetEnabled: ((Boolean) -> Unit)? = null
) {
    val isEdit = editingGoal != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun animatedDismiss() {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    var name by remember { mutableStateOf(editingGoal?.name ?: "") }
    var type by remember { mutableStateOf(editingGoal?.type ?: "daily") }
    var isNegative by remember { mutableStateOf(editingGoal?.isNegative ?: false) }
    var timesPerDay by remember { mutableStateOf(editingGoal?.timesPerDay?.toString() ?: "") }
    var timesPerWeek by remember { mutableStateOf(editingGoal?.timesPerWeek?.toString() ?: "") }
    data class RuleEntry(val minComp: String, val reward: String)
    var rewardRules by remember {
        mutableStateOf(
            editingGoal?.rewardRules?.map {
                RuleEntry(
                    it.minCompletions.toString(),
                    if (it.rewardAmount == it.rewardAmount.toLong().toDouble())
                        it.rewardAmount.toLong().toString()
                    else it.rewardAmount.toString()
                )
            } ?: emptyList<RuleEntry>()
        )
    }
    var enabled by remember { mutableStateOf(editingGoalWeek?.enabled ?: true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(validationError) {
        if (validationError != null) {
            delay(3000)
            validationError = null
            nameError = false
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete goal?", color = TextColor, fontFamily = SyneFont, fontWeight = FontWeight.Bold) },
            text = { Text("This will remove \"$name\" and all its history.", color = Text2Color, fontFamily = SyneFont) },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(); showDeleteConfirm = false }) {
                    Text("DELETE", color = RedColor, fontWeight = FontWeight.Bold, fontFamily = SyneFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("CANCEL", color = Text2Color, fontFamily = SyneFont)
                }
            },
            containerColor = SurfaceColor,
            tonalElevation = 0.dp
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceColor,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 32.dp)
            ) {
                // Title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEdit) "EDIT GOAL" else "NEW GOAL",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.06.sp,
                        color = TextColor,
                        fontFamily = SyneFont
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (isEdit && onDelete != null) {
                            Text(
                                text = "Delete",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RedColor,
                                fontFamily = SyneFont,
                                modifier = Modifier.clickable { showDeleteConfirm = true }.padding(4.dp)
                            )
                        }
                        if (isEdit && onSetEnabled != null) {
                            GoalSwitch(
                                checked = enabled,
                                onCheckedChange = { newVal -> enabled = newVal; onSetEnabled(newVal) }
                            )
                        }
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Text3Color,
                            modifier = Modifier.size(20.dp).clickable { animatedDismiss() }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                FormField(label = "NAME") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; nameError = false; validationError = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Morning run", color = Text3Color, fontFamily = SyneFont) },
                        isError = nameError,
                        colors = goalTextFieldColors(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                    )
                }

                Spacer(Modifier.height(16.dp))

                FormField(label = "TYPE") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TypeOption("DAILY", type == "daily", { type = "daily" }, Modifier.weight(1f))
                        TypeOption("WEEKLY", type == "weekly_x", { type = "weekly_x" }, Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (type == "daily") {
                    FormField(label = "TIMES PER DAY") {
                        OutlinedTextField(
                            value = timesPerDay,
                            onValueChange = { timesPerDay = it; validationError = null },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                            colors = goalTextFieldColors(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                } else {
                    FormField(label = "TIMES PER WEEK") {
                        OutlinedTextField(
                            value = timesPerWeek,
                            onValueChange = { timesPerWeek = it; validationError = null },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                            colors = goalTextFieldColors(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2Color)
                        .border(1.dp, Border2Color, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Negative goal (avoid habit)",
                        fontSize = 13.sp,
                        color = Text2Color,
                        fontFamily = SyneFont,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    GoalSwitch(checked = isNegative, onCheckedChange = { isNegative = it })
                }

                Spacer(Modifier.height(16.dp))

                val maxCompletions = if (type == "weekly_x") (timesPerWeek.toIntOrNull() ?: 7) else 7
                FormField(label = "REWARD RULES") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rewardRules.forEachIndexed { index, rule ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = rule.minComp,
                                    onValueChange = { new ->
                                        rewardRules = rewardRules.mapIndexed { i, r -> if (i == index) r.copy(minComp = new) else r }
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("min", color = Text3Color, fontFamily = DmMonoFont, fontSize = 13.sp) },
                                    label = { Text("completions", color = Text3Color, fontSize = 10.sp, fontFamily = SyneFont) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                                    colors = goalTextFieldColors(),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true
                                )
                                Text("/$maxCompletions →", fontSize = 12.sp, color = Text3Color, fontFamily = DmMonoFont)
                                OutlinedTextField(
                                    value = rule.reward,
                                    onValueChange = { new ->
                                        rewardRules = rewardRules.mapIndexed { i, r -> if (i == index) r.copy(reward = new) else r }
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("amount", color = Text3Color, fontFamily = DmMonoFont, fontSize = 13.sp) },
                                    label = { Text("reward", color = Text3Color, fontSize = 10.sp, fontFamily = SyneFont) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
                                    colors = goalTextFieldColors(),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true
                                )
                                IconButton(
                                    onClick = { rewardRules = rewardRules.filterIndexed { i, _ -> i != index } },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove rule", tint = Text3Color, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Border2Color, RoundedCornerShape(8.dp))
                                .clickable { rewardRules = rewardRules + RuleEntry("", "") }
                                .padding(vertical = 8.dp, horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+ ADD RULE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.sp, color = Text3Color, fontFamily = SyneFont)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Border2Color, RoundedCornerShape(8.dp))
                            .clickable { animatedDismiss() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("CANCEL", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.sp, color = Text2Color, fontFamily = SyneFont)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentColor)
                            .clickable {
                                val error = validateForm(
                                    name = name,
                                    type = type,
                                    timesPerDay = timesPerDay,
                                    timesPerWeek = timesPerWeek,
                                    rewardRules = rewardRules.map { it.minComp to it.reward },
                                    maxCompletions = maxCompletions
                                )
                                if (error != null) {
                                    nameError = name.isBlank()
                                    validationError = error
                                    return@clickable
                                }
                                validationError = null
                                nameError = false
                                val parsedRules = rewardRules.map { rule ->
                                    RewardRule(rule.minComp.toInt(), rule.reward.toDouble())
                                }
                                onSave(GoalFormData(
                                    name = name.trim(),
                                    type = type,
                                    isNegative = isNegative,
                                    timesPerDay = timesPerDay.toIntOrNull() ?: 1,
                                    timesPerWeek = timesPerWeek.toIntOrNull() ?: 3,
                                    rewardRules = parsedRules,
                                    enabled = enabled
                                ))
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("SAVE", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.sp, color = BgColor, fontFamily = SyneFont)
                    }
                }
            }

            // Validation toast
            androidx.compose.animation.AnimatedVisibility(
                visible = validationError != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, start = 20.dp, end = 20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RedColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = validationError ?: "",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SyneFont
                    )
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.15.sp,
            color = Text3Color,
            fontFamily = SyneFont,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun TypeOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentDim else Color.Transparent)
            .border(1.dp, if (selected) AccentColor else Border2Color, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.sp,
            color = if (selected) AccentColor else Text2Color,
            fontFamily = SyneFont
        )
    }
}

@Composable
fun GoalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = BgColor,
            checkedTrackColor = AccentColor,
            uncheckedThumbColor = BgColor,
            uncheckedTrackColor = Border2Color,
            uncheckedBorderColor = Border2Color,
            checkedBorderColor = AccentColor
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun goalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextColor,
    unfocusedTextColor = TextColor,
    focusedBorderColor = AccentColor,
    unfocusedBorderColor = Border2Color,
    cursorColor = AccentColor,
    selectionColors = TextSelectionColors(
        handleColor = Color.Transparent,
        backgroundColor = AccentColor.copy(alpha = 0.3f)
    ),
    focusedContainerColor = Surface2Color,
    unfocusedContainerColor = Surface2Color,
    errorBorderColor = RedColor
)
