package com.goals.app.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetDataStore by preferencesDataStore(name = "widget_sessions")

@Singleton
class WidgetSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyApp = stringPreferencesKey("app_session_id")
    private val keyWidget = stringPreferencesKey("widget_session_id")

    @Volatile private var cachedApp: String? = null
    @Volatile private var cachedWidget: String? = null

    suspend fun appSessionId(): String {
        cachedApp?.let { return it }
        val id = loadOrGenerate(keyApp)
        cachedApp = id
        return id
    }

    suspend fun widgetSessionId(): String {
        cachedWidget?.let { return it }
        val id = loadOrGenerate(keyWidget)
        cachedWidget = id
        return id
    }

    /** Synchronous read for OkHttp interceptors (background thread only).
     *  Returns the cached value if available; otherwise blocks briefly to load from disk. */
    fun appSessionIdNow(): String {
        cachedApp?.let { return it }
        return runBlocking { appSessionId() }
    }

    fun widgetSessionIdNow(): String {
        cachedWidget?.let { return it }
        return runBlocking { widgetSessionId() }
    }

    /** Eager warm-up called once on app start in a background coroutine. */
    suspend fun prewarm() {
        appSessionId()
        widgetSessionId()
    }

    private suspend fun loadOrGenerate(key: androidx.datastore.preferences.core.Preferences.Key<String>): String {
        val existing = context.widgetDataStore.data.first()[key]
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.widgetDataStore.edit { it[key] = fresh }
        return fresh
    }
}
