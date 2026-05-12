package com.goals.app.widget

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
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
        if (event != "log_changed") return false
        return try {
            val data = Gson().fromJson(payload, LogChangedPayload::class.java) ?: return false
            val cache = EntryPointAccessors.fromApplication(
                applicationContext,
                WidgetProviderEntryPoint::class.java
            ).cache()
            runBlocking { cache.hydrate() }
            cache.applyAuthoritativeLogs(data.goalId, data.logs, data.seq)
            cache.markFcmReceived()
            true
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
