package com.goals.app.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goals.app.data.api.GoalsApi
import com.goals.app.data.models.ToggleSlotRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Named

private const val TAG = "WidgetToggleWorker"

@HiltWorker
class WidgetToggleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @Named("widget") private val api: GoalsApi,
    private val cache: WidgetCache
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val goalId = inputData.getString(K_GOAL) ?: return Result.success()
        val date = inputData.getString(K_DATE) ?: return Result.success()
        val slotIndex = inputData.getInt(K_SLOT, -1)
        val desired = inputData.getBoolean(K_DESIRED, false)
        if (slotIndex < 0) return Result.success()

        return try {
            doToggle(goalId, date, slotIndex, desired)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // WorkManager cancelled us (e.g. REPLACE policy from a new tap, or system).
            // Clear the in-flight key so the UI doesn't get stuck dim.
            cache.clearToggling(goalId, date, slotIndex)
            throw ce
        }
    }

    private suspend fun doToggle(goalId: String, date: String, slotIndex: Int, desired: Boolean): Result {
        return try {
            val response = api.toggleSlot(ToggleSlotRequest(goalId, date, slotIndex, desired))
            if (response.isSuccessful) {
                val payload = response.body()
                if (payload != null) {
                    cache.applyAuthoritativeLogs(payload.goalId, payload.logs, payload.seq)
                }
                cache.clearToggling(goalId, date, slotIndex)
                WidgetUpdater.notifyListAndHeader(applicationContext, refreshClicks = false)
                Result.success()
            } else {
                Log.w(TAG, "Toggle failed code=${response.code()}")
                cache.clearToggling(goalId, date, slotIndex)
                if (response.code() == 409) {
                    // Out of sync. Silently refresh; the pending visual already clears.
                    WidgetUpdater.requestRefresh(applicationContext)
                } else {
                    toast("Couldn't save (${response.code()})")
                    WidgetUpdater.notifyListAndHeader(applicationContext, refreshClicks = false)
                }
                Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Toggle exception: ${e.message}")
            if (runAttemptCount >= 2) {
                cache.clearToggling(goalId, date, slotIndex)
                WidgetUpdater.notifyListAndHeader(applicationContext, refreshClicks = false)
                toast("No connection")
                Result.success()
            } else {
                Result.retry()
            }
        }
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val K_GOAL = "goal_id"
        const val K_DATE = "date"
        const val K_SLOT = "slot_index"
        const val K_DESIRED = "desired"
    }
}
