package com.goals.app.widget

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.goals.app.data.models.DayChangedPayload
import com.goals.app.data.models.GoalChangedPayload
import com.goals.app.data.models.LogChangedPayload
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

private const val TAG = "FcmService"

class FcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val event = message.data["event"]
        val payload = message.data["payload"]
        Log.i(TAG, "FCM event=$event hasPayload=${payload != null}")

        val appliedLocally = payload != null && tryApplyLocally(event, payload)
        if (appliedLocally) {
            WidgetUpdater.notifyListAndHeader(applicationContext, refreshClicks = false)
        } else {
            enqueueRefresh()
        }
    }

    private fun tryApplyLocally(event: String?, payload: String): Boolean {
        return try {
            val cache = EntryPointAccessors.fromApplication(
                applicationContext,
                WidgetProviderEntryPoint::class.java
            ).cache()
            runBlocking { cache.hydrate() }
            val gson = Gson()
            val applied = when (event) {
                "log_changed" -> {
                    val data = gson.fromJson(payload, LogChangedPayload::class.java) ?: return false
                    cache.applyAuthoritativeLogs(data.goalId, data.logs, data.seq)
                    true
                }
                "goal_changed" -> {
                    // Payload may be a single GoalChangedPayload or an array
                    // (reorder batch). Try array first, fall back to single.
                    val trimmed = payload.trimStart()
                    if (trimmed.startsWith("[")) {
                        val list = gson.fromJson(
                            payload,
                            Array<GoalChangedPayload>::class.java
                        ) ?: return false
                        list.all { cache.applyGoalChanged(
                            action = it.action,
                            seq = it.seq,
                            goal = it.goal,
                            goalId = it.goalId,
                            goalWeek = it.goalWeek,
                            logs = it.logs,
                            reorderedGoals = it.reorderedGoals
                        ) }
                    } else {
                        val data = gson.fromJson(payload, GoalChangedPayload::class.java) ?: return false
                        cache.applyGoalChanged(
                            action = data.action,
                            seq = data.seq,
                            goal = data.goal,
                            goalId = data.goalId,
                            goalWeek = data.goalWeek,
                            logs = data.logs,
                            reorderedGoals = data.reorderedGoals
                        )
                    }
                }
                "day_changed" -> {
                    val data = gson.fromJson(payload, DayChangedPayload::class.java) ?: return false
                    cache.applyDayChanged(data.date, data.logs, data.seq)
                }
                else -> return false
            }
            if (applied) cache.markFcmReceived()
            applied
        } catch (e: Exception) {
            Log.w(TAG, "Local apply failed: ${e.message}")
            false
        }
    }

    private fun enqueueRefresh() {
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "widget-refresh-fcm",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    override fun onNewToken(token: String) {
        val req = OneTimeWorkRequestBuilder<DeviceRegistrationWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf(DeviceRegistrationWorker.K_TOKEN to token))
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "device-register",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
