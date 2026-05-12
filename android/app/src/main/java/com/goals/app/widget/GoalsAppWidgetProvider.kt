package com.goals.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

private const val TAG = "GoalsWidget"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetProviderEntryPoint {
    fun cache(): WidgetCache
}

class GoalsAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.i(TAG, "onUpdate ids=${appWidgetIds.toList()}")
        try {
            val cache = cacheFor(context)
            runBlocking { cache.hydrate() }
            val snapshot = cache.snapshot()
            for (id in appWidgetIds) {
                val root = WidgetRenderer.buildRoot(context, id, snapshot)
                appWidgetManager.updateAppWidget(id, root)
            }
            if (appWidgetIds.isNotEmpty()) {
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, com.goals.app.R.id.header_list)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, com.goals.app.R.id.goal_list)
            }
            WidgetUpdater.requestRefresh(context)
            WidgetUpdater.schedulePeriodicRefresh(context)
        } catch (t: Throwable) {
            Log.e(TAG, "onUpdate failed", t)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.requestRefresh(context)
        WidgetUpdater.schedulePeriodicRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.cancelPeriodicRefresh(context)
    }

    private fun cacheFor(context: Context): WidgetCache =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetProviderEntryPoint::class.java
        ).cache()

    companion object {
        fun widgetIds(context: Context): IntArray {
            val mgr = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, GoalsAppWidgetProvider::class.java)
            return mgr.getAppWidgetIds(cn) ?: IntArray(0)
        }
    }
}
