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

private const val TAG = "WidgetRefreshWorker"
private const val FCM_FRESHNESS_WINDOW_MS = 15L * 60 * 1000

@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @Named("widget") private val api: GoalsApi,
    private val cache: WidgetCache
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val isPeriodic = inputData.getBoolean(K_PERIODIC, false)
        // For the periodic poll only: skip if we already heard from FCM recently.
        // Explicit refresh paths (FCM fallback, 409 recovery, boot, week-nav) always run.
        if (isPeriodic) {
            cache.hydrate()
            val lastFcm = cache.snapshot().lastFcmAtMs
            if (lastFcm > 0 && System.currentTimeMillis() - lastFcm < FCM_FRESHNESS_WINDOW_MS) {
                Log.i(TAG, "Skipping periodic refresh; FCM event ${(System.currentTimeMillis() - lastFcm)}ms ago")
                return Result.success()
            }
        }
        return try {
            cache.hydrate()
            val response = api.init()
            if (!response.isSuccessful) {
                Log.w(TAG, "init failed code=${response.code()}")
                return Result.retry()
            }
            val data = response.body() ?: return Result.retry()
            val payloadWeekStart = data.goalWeeks.firstOrNull()?.weekStart ?: ""
            val today = WidgetClock.today(data.settings)
            // The widget's selected date is user-owned: only an explicit tap moves it.
            // /api/init always returns the *current* week, so when the user has parked
            // the widget on another week we must not stamp that payload over it — doing
            // so left weekStart in one week and selectedDate in another, which rendered
            // a day strip with no highlighted day and blanked the goal rows.
            val committed = cache.applyAndGet { s ->
                val selected = s.selectedDate.ifEmpty { today }
                val desiredWeekStart =
                    if (selected.isNotEmpty())
                        WidgetDates.weekStartFor(selected, data.settings.firstDayOfWeek)
                    else payloadWeekStart
                // Week-independent fields are always safe to refresh.
                val base = s.copy(
                    goals = data.goals.sortedBy { it.order },
                    today = today,
                    selectedDate = selected,
                    settings = data.settings,
                    lastSeq = data.seq
                )
                if (desiredWeekStart == payloadWeekStart) {
                    base.copy(
                        goalWeeks = data.goalWeeks,
                        logs = data.logs,
                        weekStart = payloadWeekStart
                    )
                } else {
                    // Payload is for a week we're not showing. Keep the cached week's
                    // rows and fetch the right one instead of rendering wrong data.
                    base
                }
            }
            WidgetUpdater.notifyListAndHeader(applicationContext)
            // Derived from the *committed* snapshot, never from a var mutated inside
            // the CAS lambda (which may run more than once).
            val neededWeek = WidgetDates.weekStartFor(
                committed.selectedDate.ifEmpty { today },
                committed.settings?.firstDayOfWeek
            )
            if (committed.weekStart != neededWeek) {
                WidgetUpdater.requestWeekFetch(applicationContext, neededWeek, selectedDate = null)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Refresh exception: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val K_PERIODIC = "periodic"
    }
}
