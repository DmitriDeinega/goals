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
            val weekStart = data.goalWeeks.firstOrNull()?.weekStart ?: ""
            val today = WidgetClock.today(data.settings)
            cache.apply { s ->
                s.copy(
                    goals = data.goals.sortedBy { it.order },
                    goalWeeks = data.goalWeeks,
                    logs = data.logs,
                    weekStart = weekStart,
                    today = today,
                    selectedDate = s.selectedDate.ifEmpty { today },
                    settings = data.settings,
                    lastSeq = data.seq
                )
            }
            WidgetUpdater.notifyListAndHeader(applicationContext)
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
