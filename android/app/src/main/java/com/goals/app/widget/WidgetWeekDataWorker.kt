package com.goals.app.widget

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goals.app.data.api.GoalsApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Named

private const val TAG = "WidgetWeekDataWorker"

@HiltWorker
class WidgetWeekDataWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @Named("widget") private val api: GoalsApi,
    private val cache: WidgetCache
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val weekStart = inputData.getString(K_WEEK_START) ?: return Result.success()
        val selectedDate = inputData.getString(K_SELECTED_DATE)
        return try {
            cache.hydrate()
            val response = api.getWeekData(weekStart)
            if (!response.isSuccessful) {
                Log.w(TAG, "week-data failed code=${response.code()}")
                return Result.retry()
            }
            val data = response.body() ?: return Result.retry()
            val committed = cache.applyAndGet { s ->
                // The week we fetched is only useful if the *live* selection still
                // belongs to it. Deciding from this request's own input instead would
                // always pass, which let a slow cross-week response reverse a newer
                // tap. Reading s.selectedDate inside the CAS lambda means the check is
                // re-evaluated against the winning snapshot on contention.
                val desiredSel = s.selectedDate.ifEmpty { selectedDate ?: "" }
                if (desiredSel.isEmpty()) return@applyAndGet s
                val liveWeekStart =
                    WidgetDates.weekStartFor(desiredSel, s.settings?.firstDayOfWeek)
                if (liveWeekStart != weekStart) return@applyAndGet s

                s.copy(
                    goalWeeks = data.goalWeeks,
                    logs = data.logs,
                    weekStart = weekStart,
                    selectedDate = desiredSel
                )
            }
            // Derived from the committed result — the CAS lambda may run repeatedly,
            // so a flag mutated inside it can report work that was never committed.
            if (committed.weekStart == weekStart) {
                WidgetUpdater.notifyListAndHeader(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "exception: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val K_WEEK_START = "week_start"
        const val K_SELECTED_DATE = "selected_date"
    }
}
