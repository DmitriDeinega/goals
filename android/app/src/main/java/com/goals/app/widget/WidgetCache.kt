package com.goals.app.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        while (true) {
            val current = ref.get()
            val next = update(current)
            if (ref.compareAndSet(current, next)) {
                _flow.value = next
                if (next.hydrated) persist(next)
                return
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
