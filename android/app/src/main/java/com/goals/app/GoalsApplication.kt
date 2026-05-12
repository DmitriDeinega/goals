package com.goals.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.goals.app.widget.WidgetSessionStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GoalsApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessions: WidgetSessionStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Pre-warm session IDs on a background coroutine. Cold-start receivers/interceptors
        // can then read the cached values synchronously without hitting DataStore.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sessions.prewarm()
        }
    }
}
