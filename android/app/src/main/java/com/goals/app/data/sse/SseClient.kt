package com.goals.app.data.sse

import android.util.Log
import com.goals.app.data.api.NetworkModule
import com.goals.app.widget.WidgetSessionStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SSE"

sealed class SseMessage {
    data class Event(val type: String, val data: String) : SseMessage()
    data class Error(val t: Throwable?) : SseMessage()
    object Closed : SseMessage()
}

@Singleton
class SseClient @Inject constructor(
    private val sessions: WidgetSessionStore
) {

    private val sseHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Session-ID", NetworkModule.appSessionId(sessions))
                .build()
            chain.proceed(request)
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(serverUrl: String): Flow<SseMessage> = callbackFlow {
        val sessionId = NetworkModule.appSessionId(sessions)
        val url = "${serverUrl.trimEnd('/')}/api/events/?session_id=$sessionId"
        Log.i(TAG, "Connecting to $url")
        val request = Request.Builder().url(url).build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "Connected (HTTP ${response.code})")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val eventType = type ?: "message"
                Log.d(TAG, "Event: type=$eventType data=${data.take(120)}")
                trySend(SseMessage.Event(eventType, data))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "Failure: code=${response?.code} error=${t?.message}")
                trySend(SseMessage.Error(t))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                Log.i(TAG, "Closed by server")
                trySend(SseMessage.Closed)
                close()
            }
        }

        val eventSource = EventSources.createFactory(sseHttpClient)
            .newEventSource(request, listener)

        awaitClose {
            Log.i(TAG, "Cancelling SSE")
            eventSource.cancel()
        }
    }
}
