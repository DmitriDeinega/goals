package com.goals.app.widget

import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.goals.app.R
import com.goals.app.data.models.GoalWeek
import kotlinx.coroutines.runBlocking

class WidgetRemoteViewsFactory(
    private val context: Context,
    private val cache: WidgetCache,
    @Suppress("UNUSED_PARAMETER") appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var items: List<GoalWeek> = emptyList()

    @Volatile
    private var snapshot: WidgetSnapshot = WidgetSnapshot()

    override fun onCreate() {
        runBlocking { cache.hydrate() }
        refresh()
    }

    override fun onDataSetChanged() {
        refresh()
    }

    private fun refresh() {
        val snap = cache.snapshot()
        snapshot = snap
        items = snap.goalWeeks
            .filter { it.enabled }
            .sortedBy { it.snapshot.order }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items.getOrNull(position) ?: return loadingView()
        return WidgetRenderer.buildRow(context, snapshot, item)
    }

    override fun getLoadingView(): RemoteViews = loadingView()

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        val gw = items.getOrNull(position) ?: return position.toLong()
        return WidgetIds.stableItemId(gw.goalId)
    }

    override fun hasStableIds(): Boolean = true

    private fun loadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_row)
}
