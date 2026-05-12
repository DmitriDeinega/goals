package com.goals.app.widget

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goals.app.data.api.DEFAULT_SERVER_URL
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DeviceRegWorker"

@HiltWorker
class DeviceRegistrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sessions: WidgetSessionStore
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(K_TOKEN) ?: return Result.success()
        return try {
            val appSession = sessions.appSessionId()
            val widgetSession = sessions.widgetSessionId()
            val body = Gson().toJson(
                mapOf(
                    "token" to token,
                    "app_session_id" to appSession,
                    "widget_session_id" to widgetSession,
                    "platform" to "android"
                )
            )
            val url = "${DEFAULT_SERVER_URL.trimEnd('/')}/api/devices/register"
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = OkHttpClient().newCall(request).execute()
            response.use {
                if (it.isSuccessful) Result.success() else Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Register exception: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val K_TOKEN = "token"
    }
}
