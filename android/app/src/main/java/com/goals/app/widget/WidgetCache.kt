package com.goals.app.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goals.app.data.models.Goal
import com.goals.app.data.models.GoalLog
import com.goals.app.data.models.GoalWeek
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WidgetCache"
private val Context.cacheDataStore by preferencesDataStore(name = "widget_cache")

@Singleton
class WidgetCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val key = stringPreferencesKey("snapshot_v1")
    private val ref = AtomicReference(WidgetSnapshot())
    private val _flow = MutableStateFlow(WidgetSnapshot())
    val flow: StateFlow<WidgetSnapshot> = _flow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun snapshot(): WidgetSnapshot = ref.get()

    suspend fun hydrate() {
        if (ref.get().hydrated) return
        val raw = context.cacheDataStore.data.first()[key]
        val loaded = if (raw != null) {
            try {
                gson.fromJson(raw, WidgetSnapshot::class.java)
                    ?.copy(hydrated = true, inFlightToggles = emptySet())
                    ?: WidgetSnapshot(hydrated = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cache: ${e.message}")
                WidgetSnapshot(hydrated = true)
            }
        } else WidgetSnapshot(hydrated = true)
        while (true) {
            val current = ref.get()
            if (current.hydrated) {
                _flow.value = current
                return
            }
            val merged = loaded.copy(inFlightToggles = current.inFlightToggles)
            if (ref.compareAndSet(current, merged)) {
                _flow.value = merged
                persist(merged)
                return
            }
        }
    }

    fun replace(next: WidgetSnapshot) {
        val toStore = next.copy(hydrated = true)
        ref.set(toStore)
        _flow.value = toStore
        persist(toStore)
    }

    fun apply(update: (WidgetSnapshot) -> WidgetSnapshot) {
        applyAndGet(update)
    }

    /**
     * Same as [apply], but returns the snapshot that was actually committed.
     *
     * Callers that need to act on what the update decided (e.g. "was the cached
     * week left stale, so should I schedule a fetch?") MUST derive that from this
     * return value rather than mutating a captured var inside the lambda: [update]
     * is re-invoked on CAS contention, so a side effect from a discarded attempt
     * would leak out and act on a decision that was never committed.
     */
    fun applyAndGet(update: (WidgetSnapshot) -> WidgetSnapshot): WidgetSnapshot {
        while (true) {
            val current = ref.get()
            val next = update(current)
            if (ref.compareAndSet(current, next)) {
                _flow.value = next
                if (next.hydrated) persist(next)
                return next
            }
        }
    }

    fun applyAuthoritativeLogs(goalId: String, updatedLogs: List<GoalLog>, seq: Long) {
        apply { s ->
            if (seq < s.lastSeq) return@apply s
            val updatedDates = updatedLogs.map { it.date }.toSet()
            val kept = s.logs.filter { it.goalId != goalId || it.date !in updatedDates }
            s.copy(logs = kept + updatedLogs, lastSeq = maxOf(s.lastSeq, seq))
        }
    }

    /** Apply a `goal_changed` event payload to the cached snapshot directly,
     *  so the widget stays in sync without scheduling a network refresh.
     *  Mirrors AppViewModel.applyGoalChanged. Returns false if the payload's
     *  seq is stale (caller should fall back to a full refresh). */
    fun applyGoalChanged(
        action: String,
        seq: Long,
        goal: Goal?,
        goalId: String?,
        goalWeek: GoalWeek?,
        logs: List<GoalLog>?,
        reorderedGoals: List<com.goals.app.data.models.ReorderItem>?
    ): Boolean {
        var stale = false
        apply { s ->
            if (seq < s.lastSeq) { stale = true; return@apply s }
            val visibleWeekStart = s.weekStart
            var nextGoals = s.goals
            var nextGoalWeeks = s.goalWeeks
            var nextLogs = s.logs
            when (action) {
                "created" -> {
                    if (goal != null) nextGoals = (nextGoals + goal).sortedBy { it.order }
                    if (goalWeek != null && goalWeek.weekStart == visibleWeekStart) {
                        nextGoalWeeks = nextGoalWeeks + goalWeek
                        if (!logs.isNullOrEmpty()) nextLogs = nextLogs + logs
                    }
                }
                "updated" -> {
                    if (goal != null) nextGoals = nextGoals.map { if (it.id == goal.id) goal else it }.sortedBy { it.order }
                    if (goalWeek != null && goalWeek.weekStart == visibleWeekStart) {
                        nextGoalWeeks = nextGoalWeeks.map { if (it.goalId == goalWeek.goalId) goalWeek else it }
                        if (!logs.isNullOrEmpty() && goal != null) {
                            val updatedDates = logs.map { it.date }.toSet()
                            nextLogs = nextLogs.filter { it.goalId != goal.id || it.date !in updatedDates } + logs
                        }
                    }
                }
                "deleted" -> {
                    val deletedId = goalId ?: goal?.id ?: return@apply s
                    nextGoals = nextGoals.filter { it.id != deletedId }
                    if (!reorderedGoals.isNullOrEmpty()) {
                        val orderMap = reorderedGoals.associate { it.goalId to it.newOrder }
                        nextGoals = nextGoals.map { g -> orderMap[g.id]?.let { g.copy(order = it) } ?: g }.sortedBy { it.order }
                    }
                    nextGoalWeeks = nextGoalWeeks.filter { it.goalId != deletedId }
                    nextLogs = nextLogs.filter { it.goalId != deletedId }
                }
                "enabled_changed" -> {
                    if (goalWeek != null && goalWeek.weekStart == visibleWeekStart) {
                        nextGoalWeeks = nextGoalWeeks.map { if (it.goalId == goalWeek.goalId) goalWeek else it }
                    }
                }
                "reordered" -> {
                    if (goalId != null && reorderedGoals == null) {
                        // single-goal reorder: backend may send action=reordered with new_order on payload;
                        // the widget receives a list (one per goal), each handled here. Fall back to refresh
                        // if shape isn't recognized.
                        return@apply s
                    }
                }
                else -> return@apply s
            }
            s.copy(
                goals = nextGoals,
                goalWeeks = nextGoalWeeks,
                logs = nextLogs,
                lastSeq = maxOf(s.lastSeq, seq)
            )
        }
        return !stale
    }

    fun applyDayChanged(date: String, newLogs: List<GoalLog>, seq: Long): Boolean {
        var stale = false
        apply { s ->
            if (seq < s.lastSeq) { stale = true; return@apply s }
            val kept = s.logs.filter { it.date != date }
            s.copy(
                today = date,
                logs = kept + newLogs,
                lastSeq = maxOf(s.lastSeq, seq)
            )
        }
        return !stale
    }

    fun replaceWeek(goalWeeks: List<GoalWeek>, logs: List<GoalLog>, weekStart: String) {
        apply { s -> s.copy(goalWeeks = goalWeeks, logs = logs, weekStart = weekStart) }
    }

    /** Pessimistic-toggle pending-state tracking. */
    fun markToggling(goalId: String, date: String, slotIndex: Int) {
        val k = WidgetSnapshot.toggleKey(goalId, date, slotIndex)
        apply { s -> if (k in s.inFlightToggles) s else s.copy(inFlightToggles = s.inFlightToggles + k) }
    }

    fun clearToggling(goalId: String, date: String, slotIndex: Int) {
        val k = WidgetSnapshot.toggleKey(goalId, date, slotIndex)
        apply { s -> if (k !in s.inFlightToggles) s else s.copy(inFlightToggles = s.inFlightToggles - k) }
    }

    fun markFcmReceived() {
        apply { s -> s.copy(lastFcmAtMs = System.currentTimeMillis()) }
    }

    private fun persist(s: WidgetSnapshot) {
        scope.launch {
            try {
                val toSave = s.copy(inFlightToggles = emptySet())
                val json = gson.toJson(toSave)
                context.cacheDataStore.edit { it[key] = json }
            } catch (e: Exception) {
                Log.w(TAG, "Persist failed: ${e.message}")
            }
        }
    }
}
