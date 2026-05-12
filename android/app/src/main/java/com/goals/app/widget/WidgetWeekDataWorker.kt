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
            var applied = false
            cache.apply { s ->
                // Atomic CAS safety check: only apply if the user's current selected
                // date is still within the week we just fetched. Otherwise the user
                // has navigated again and this fetch is stale.
                val firstDay = s.settings?.firstDayOfWeek
                val desiredSel = selectedDate ?: s.selectedDate
                val desiredWeekStart = if (desiredSel.isNotEmpty())
                    WidgetDates.weekStartFor(desiredSel, firstDay) else weekStart
                if (desiredWeekStart != weekStart) {
                    return@apply s
                }
                applied = true
                s.copy(
                    goalWeeks = data.goalWeeks,
                    logs = data.logs,
                    weekStart = weekStart,
                    selectedDate = selectedDate ?: s.selectedDate
                )
            }
            if (applied) {
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
