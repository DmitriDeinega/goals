package com.goals.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goals.app.data.api.DEFAULT_SERVER_URL
import com.goals.app.data.api.NetworkModule
import com.goals.app.data.models.*
import com.goals.app.data.models.ReorderedPayload
import com.goals.app.data.sse.SseClient
import com.goals.app.data.sse.SseMessage
import com.goals.app.repository.ApiResult
import com.goals.app.repository.GoalsRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

// ── State ──────────────────────────────────────────────────────────────────────

data class WeekState(
    val goalWeeks: List<GoalWeek> = emptyList(),
    val logs: List<GoalLog> = emptyList(),
    val weekStart: String? = null
)

data class UiState(
    val goals: List<Goal> = emptyList(),
    val settings: AppSettings? = null,
    val loading: Boolean = true,
    val week: WeekState = WeekState(),
    val toast: String? = null,
    val today: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
)

// ── Calculation helpers ────────────────────────────────────────────────────────

data class GoalStats(val completions: Int, val totalSlots: Int, val earnedReward: Double)
data class WeekSummary(val pct: Int, val totalEarned: Double)

fun getDaysUpTo(weekStart: String, cutoff: String): List<String> {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val start = LocalDate.parse(weekStart, fmt)
    val end = LocalDate.parse(cutoff, fmt)
    val days = mutableListOf<String>()
    var cur = start
    while (!cur.isAfter(end)) {
        days.add(cur.format(fmt))
        cur = cur.plusDays(1)
    }
    return days
}

fun computeGoalStats(goal: GoalWeekSnapshot, goalId: String, weekLogs: List<GoalLog>, weekDays: List<String>): GoalStats {
    val isNeg = goal.isNegative
    val goalLogs = weekLogs.filter { it.goalId == goalId }
    var completions = 0
    val totalSlots: Int

    if (goal.type == "daily") {
        val tpd = goal.timesPerDay ?: 1
        if (tpd > 1) {
            completions = weekDays.sumOf { d ->
                val log = goalLogs.find { it.date == d } ?: return@sumOf 0
                val success = if (isNeg) log.slots.any { s -> s } else log.slots.all { s -> s }
                if (success) 1 else 0 as Int
            }
        } else {
            if (isNeg) {
                val failedDays = goalLogs.filter { it.slots.any { s -> !s } }.map { it.date }.toSet()
                completions = weekDays.count { it !in failedDays }
            } else {
                completions = goalLogs.count { it.date in weekDays && it.slots.any { s -> s } }
            }
        }
        totalSlots = 7
    } else {
        val tpw = goal.timesPerWeek ?: 7
        if (isNeg) {
            val failedDays = goalLogs.filter { it.slots.any { s -> !s } }.map { it.date }.toSet()
            completions = weekDays.count { it !in failedDays }
        } else {
            completions = goalLogs.count { it.date in weekDays && it.slots.any { s -> s } }
        }
        totalSlots = tpw
    }

    val rewardCompletions = if (goal.type == "weekly_x") minOf(completions, goal.timesPerWeek ?: 7) else completions
    val earnedReward = goal.rewardRules
        .sortedBy { it.minCompletions }
        .fold(0.0) { acc, r -> if (rewardCompletions >= r.minCompletions) acc + r.rewardAmount else acc }

    return GoalStats(completions, totalSlots, earnedReward)
}

fun computeWeekSummary(
    goals: List<Goal>,
    goalWeeks: List<GoalWeek>,
    logs: List<GoalLog>,
    weekStart: String,
    weekEnd: String,
    today: String
): WeekSummary {
    val enabled = goalWeeks.filter { it.enabled }
    if (enabled.isEmpty()) return WeekSummary(0, 0.0)

    val liveGoalMap = goals.associateBy { it.id }
    val cutoff = if (weekEnd < today) weekEnd else today
    val weekDays = getDaysUpTo(weekStart, cutoff)

    var totalPct = 0.0
    var totalEarned = 0.0

    for (gw in enabled) {
        val snap = gw.snapshot
        val live = liveGoalMap[gw.goalId]
        val effectiveSnap = GoalWeekSnapshot(
            name = snap.name,
            type = snap.type ?: live?.type ?: "daily",
            isNegative = snap.isNegative,
            timesPerDay = snap.timesPerDay ?: live?.timesPerDay,
            timesPerWeek = snap.timesPerWeek ?: live?.timesPerWeek,
            rewardRules = snap.rewardRules.ifEmpty { live?.rewardRules ?: emptyList() },
            order = snap.order
        )
        val stats = computeGoalStats(effectiveSnap, gw.goalId, logs, weekDays)
        totalPct += minOf(stats.completions, stats.totalSlots).toDouble() / maxOf(stats.totalSlots, 1)
        totalEarned += stats.earnedReward
    }

    return WeekSummary(
        pct = ((totalPct / enabled.size) * 100).roundToInt(),
        totalEarned = totalEarned
    )
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: GoalsRepository,
    private val sseClient: SseClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var lastSeq: Long = 0L
    private val gson = Gson()
    private var sseJob: Job? = null
    private var sseRetryDelay = 1000L


    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            repository.ensureWeek()
            when (val result = repository.init()) {
                is ApiResult.Success -> {
                    val data = result.data
                    lastSeq = data.seq
                    _uiState.update { it.copy(
                        goals = data.goals.sortedBy { g -> g.order },
                        settings = data.settings,
                        loading = false,
                        week = WeekState(
                            goalWeeks = data.goalWeeks,
                            logs = data.logs,
                            weekStart = data.goalWeeks.firstOrNull()?.weekStart
                        )
                    )}
                    startSse()
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(loading = false, toast = "Connection failed: ${result.message}") }
                }
            }
        }
    }

    fun loadWeek(weekStart: String) {
        viewModelScope.launch {
            when (val result = repository.getWeekData(weekStart)) {
                is ApiResult.Success -> {
                    val data = result.data
                    _uiState.update { it.copy(week = WeekState(
                        goalWeeks = data.goalWeeks,
                        logs = data.logs,
                        weekStart = weekStart
                    ))}
                }
                is ApiResult.Error -> showToast("Failed to load week: ${result.message}")
            }
        }
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    fun toggle(goalId: String, date: String, slotIndex: Int, currentValue: Boolean) {
        // Optimistic update
        val newValue = !currentValue
        _uiState.update { state ->
            state.copy(week = state.week.copy(
                logs = state.week.logs.map { log ->
                    if (log.goalId == goalId && log.date == date) {
                        val newSlots = log.slots.toMutableList().also { it[slotIndex] = newValue }
                        log.copy(slots = newSlots)
                    } else log
                }
            ))
        }
        viewModelScope.launch {
            val result = repository.toggleSlot(ToggleSlotRequest(goalId, date, slotIndex, newValue))
            when (result) {
                is ApiResult.Success -> {
                    lastSeq = result.data.seq
                    applyLogChanged(result.data.goalId, result.data.logs)
                }
                is ApiResult.Error -> {
                    // Revert optimistic update
                    _uiState.update { state ->
                        state.copy(week = state.week.copy(
                            logs = state.week.logs.map { log ->
                                if (log.goalId == goalId && log.date == date) {
                                    val reverted = log.slots.toMutableList().also { it[slotIndex] = currentValue }
                                    log.copy(slots = reverted)
                                } else log
                            }
                        ))
                    }
                    val msg = when (result.code) {
                        409 -> "Out of sync. Reloading..."
                        0 -> "No connection. Check your network."
                        else -> "Failed to update (${result.code})"
                    }
                    if (result.code == 409) loadData()
                    showToast(msg)
                }
            }
        }
    }

    // ── Goal actions ──────────────────────────────────────────────────────────

    fun addGoal(req: CreateGoalRequest) {
        viewModelScope.launch {
            when (val result = repository.createGoal(req)) {
                is ApiResult.Success -> {
                    repository.ensureWeek()
                    lastSeq = result.data.seq
                    applyGoalChanged(result.data)
                }
                is ApiResult.Error -> showToast("Failed to create goal: ${result.message}")
            }
        }
    }

    fun editGoal(id: String, req: UpdateGoalRequest) {
        viewModelScope.launch {
            when (val result = repository.updateGoal(id, req)) {
                is ApiResult.Success -> {
                    lastSeq = result.data.seq
                    applyGoalChanged(result.data)
                }
                is ApiResult.Error -> {
                    if (result.code == 409) {
                        showToast("Goal updated on another device. Reloading...")
                        loadData()
                    } else {
                        showToast("Failed to update: ${result.message}")
                    }
                }
            }
        }
    }

    fun deleteGoal(id: String) {
        viewModelScope.launch {
            when (val result = repository.deleteGoal(id)) {
                is ApiResult.Success -> {
                    lastSeq = result.data.seq
                    applyGoalChanged(result.data)
                }
                is ApiResult.Error -> showToast("Failed to delete: ${result.message}")
            }
        }
    }

    fun setEnabled(goalId: String, enabled: Boolean) {
        viewModelScope.launch {
            when (val result = repository.setEnabled(goalId, enabled)) {
                is ApiResult.Success -> {
                    lastSeq = result.data.seq
                    applyGoalChanged(result.data)
                }
                is ApiResult.Error -> showToast("Failed: ${result.message}")
            }
        }
    }

    fun reorder(orderedIds: List<String>) {
        // Optimistic update
        _uiState.update { state ->
            val updatedGoals = state.goals.map { g ->
                val newOrder = orderedIds.indexOf(g.id)
                if (newOrder != -1) g.copy(order = newOrder) else g
            }.sortedBy { it.order }
            val updatedWeeks = state.week.goalWeeks.map { gw ->
                val newOrder = orderedIds.indexOf(gw.goalId)
                if (newOrder != -1) gw.copy(snapshot = gw.snapshot.copy(order = newOrder)) else gw
            }
            state.copy(goals = updatedGoals, week = state.week.copy(goalWeeks = updatedWeeks))
        }
        viewModelScope.launch {
            val items = orderedIds.mapIndexed { index, goalId -> ReorderItem(goalId, index) }
            repository.reorderGoals(items)
        }
    }

    // ── State application helpers ─────────────────────────────────────────────

    private fun applyLogChanged(goalId: String, updatedLogs: List<GoalLog>) {
        _uiState.update { state ->
            val updatedDates = updatedLogs.map { it.date }.toSet()
            val kept = state.week.logs.filter { it.goalId != goalId || it.date !in updatedDates }
            state.copy(week = state.week.copy(logs = kept + updatedLogs))
        }
    }

    private fun applyGoalChanged(payload: GoalChangedPayload) {
        val visibleWeekStart = _uiState.value.week.weekStart
        _uiState.update { state ->
            var goals = state.goals
            var weekState = state.week

            when (payload.action) {
                "created" -> {
                    val goal = payload.goal ?: return@update state
                    goals = (goals + goal).sortedBy { it.order }
                    if (payload.goalWeek?.weekStart == visibleWeekStart) {
                        val newGws = if (payload.goalWeek != null) weekState.goalWeeks + payload.goalWeek else weekState.goalWeeks
                        val newLogs = if (!payload.logs.isNullOrEmpty()) weekState.logs + payload.logs else weekState.logs
                        weekState = weekState.copy(goalWeeks = newGws, logs = newLogs)
                    }
                }
                "updated" -> {
                    val goal = payload.goal ?: return@update state
                    goals = goals.map { if (it.id == goal.id) goal else it }.sortedBy { it.order }
                    val updatedGw = payload.goalWeek
                    if (updatedGw != null && updatedGw.weekStart == visibleWeekStart) {
                        val newGws = weekState.goalWeeks.map { if (it.goalId == updatedGw.goalId) updatedGw else it }
                        val newLogs = if (!payload.logs.isNullOrEmpty()) {
                            val updatedDates = payload.logs.map { it.date }.toSet()
                            weekState.logs.filter { it.goalId != goal.id || it.date !in updatedDates } + payload.logs
                        } else weekState.logs
                        weekState = weekState.copy(goalWeeks = newGws, logs = newLogs)
                    }
                }
                "deleted" -> {
                    val deletedId = payload.goalId ?: payload.goal?.id ?: return@update state
                    goals = goals.filter { it.id != deletedId }
                    if (!payload.reorderedGoals.isNullOrEmpty()) {
                        val orderMap = payload.reorderedGoals.associate { it.id to it.order }
                        goals = goals.map { g -> orderMap[g.id]?.let { g.copy(order = it) } ?: g }.sortedBy { it.order }
                    }
                    weekState = weekState.copy(
                        goalWeeks = weekState.goalWeeks.filter { it.goalId != deletedId },
                        logs = weekState.logs.filter { it.goalId != deletedId }
                    )
                }
                "enabled_changed" -> {
                    val gw = payload.goalWeek ?: return@update state
                    if (gw.weekStart == visibleWeekStart) {
                        weekState = weekState.copy(
                            goalWeeks = weekState.goalWeeks.map { if (it.goalId == gw.goalId) gw else it }
                        )
                    }
                }
            }
            state.copy(goals = goals, week = weekState)
        }
    }

    private fun applyReorder(payloads: List<ReorderedPayload>) {
        val orderMap = payloads.associate { it.goalId to it.newOrder }
        _uiState.update { state ->
            val goals = state.goals.map { g ->
                orderMap[g.id]?.let { g.copy(order = it) } ?: g
            }.sortedBy { it.order }
            val goalWeeks = state.week.goalWeeks.map { gw ->
                orderMap[gw.goalId]?.let { gw.copy(snapshot = gw.snapshot.copy(order = it)) } ?: gw
            }
            state.copy(goals = goals, week = state.week.copy(goalWeeks = goalWeeks))
        }
    }

    fun applyDayChanged(date: String, newLogs: List<GoalLog>) {
        _uiState.update { state ->
            val kept = state.week.logs.filter { it.date != date }
            state.copy(
                today = date,
                week = state.week.copy(logs = kept + newLogs)
            )
        }
    }

    // ── SSE ───────────────────────────────────────────────────────────────────

    private fun startSse() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            sseRetryDelay = 1000L
            while (true) {
                try {
                    sseClient.connect(DEFAULT_SERVER_URL).collect { msg ->
                        when (msg) {
                            is SseMessage.Event -> {
                                sseRetryDelay = 1000L
                                handleSseEvent(msg.type, msg.data)
                            }
                            is SseMessage.Error -> throw Exception("SSE error: ${msg.t?.message}")
                            is SseMessage.Closed -> throw Exception("SSE closed")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SSE", "Disconnected: ${e.message}, retrying in ${sseRetryDelay}ms")
                }
                val jitter = (Math.random() * sseRetryDelay * 0.3).toLong()
                delay(sseRetryDelay + jitter)
                sseRetryDelay = minOf(sseRetryDelay * 2, 30_000L)
                Log.i("SSE", "Reconnecting...")
            }
        }
    }

    private fun handleSseEvent(type: String, data: String) {
        Log.d("SSE", "Handling event type=$type lastSeq=$lastSeq")
        try {
            when (type) {
                "log_changed" -> {
                    val payload = gson.fromJson(data, LogChangedPayload::class.java)
                    if (!checkSeq(payload.seq)) return
                    lastSeq = payload.seq
                    applyLogChanged(payload.goalId, payload.logs)
                }
                "goal_changed" -> {
                    if (data.trimStart().startsWith("[")) {
                        val type2 = object : TypeToken<List<ReorderedPayload>>() {}.type
                        val payloads = gson.fromJson<List<ReorderedPayload>>(data, type2)
                        val lastPayload = payloads.lastOrNull() ?: return
                        if (!checkSeq(lastPayload.seq)) return
                        applyReorder(payloads)
                        lastSeq = lastPayload.seq
                    } else {
                        val payload = gson.fromJson(data, GoalChangedPayload::class.java)
                        if (!checkSeq(payload.seq)) return
                        lastSeq = payload.seq
                        applyGoalChanged(payload)
                    }
                }
                "day_changed" -> {
                    val payload = gson.fromJson(data, DayChangedPayload::class.java)
                    if (!checkSeq(payload.seq)) return
                    lastSeq = payload.seq
                    applyDayChanged(payload.date, payload.logs)
                }
                "ping" -> { /* heartbeat, ignore */ }
            }
        } catch (_: Exception) { }
    }

    private fun checkSeq(incomingSeq: Long): Boolean {
        return when {
            incomingSeq == lastSeq + 1 -> true  // expected
            incomingSeq <= lastSeq -> {
                Log.w("SSE", "Stale event: expected ${lastSeq + 1}, got $incomingSeq — ignoring")
                false  // duplicate/old, ignore
            }
            else -> {
                // Skipped ahead — widget or another device acted without us knowing
                // Just accept it rather than reload-looping
                Log.w("SSE", "Seq gap: expected ${lastSeq + 1}, got $incomingSeq — accepting")
                true
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    fun getLog(goalId: String, date: String): GoalLog? =
        _uiState.value.week.logs.find { it.goalId == goalId && it.date == date }

    fun showToast(message: String) {
        _uiState.update { it.copy(toast = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toast = null) }
    }

    // Called when app comes to foreground
    fun onPause() {
        sseJob?.cancel()
        sseJob = null
    }

    fun onResume() {
        loadData()
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
    }
}
