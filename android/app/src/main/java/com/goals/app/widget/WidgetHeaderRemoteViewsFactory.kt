package com.goals.app.widget

import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.goals.app.R
import kotlinx.coroutines.runBlocking

/**
 * One-item factory backing R.id.header_list. Updates flow through
 * notifyAppWidgetViewDataChanged(R.id.header_list), which on AOSP only
 * notifies the target AdapterView's adapter — it does not reapply the
 * widget's root RemoteViews and does not touch the sibling goal_list.
 * That's why we route header changes through here instead of
 * partiallyUpdateAppWidget: on Samsung HoneySpace, any partial update
 * triggers a prepareView that resets goal_list scroll.
 */
class WidgetHeaderRemoteViewsFactory(
    private val context: Context,
    private val cache: WidgetCache
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var snapshot: WidgetSnapshot = WidgetSnapshot()

    override fun onCreate() {
        runBlocking { cache.hydrate() }
        snapshot = cache.snapshot()
    }

    override fun onDataSetChanged() {
        snapshot = cache.snapshot()
    }

    override fun onDestroy() {}

    override fun getCount(): Int = 1

    override fun getViewAt(position: Int): RemoteViews =
        WidgetRenderer.buildHeaderItem(context, snapshot)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = 0L

    override fun hasStableIds(): Boolean = true
}
