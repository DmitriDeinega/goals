package com.goals.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.goals.app.R
import java.util.concurrent.TimeUnit

object WidgetUpdater {

    /**
     * Push header + goal-list data changes via the adapter notify path. This
     * never calls partiallyUpdateAppWidget, which on Samsung HoneySpace
     * triggers an internal prepareView that resets goal_list scroll state.
     *
     * AppWidgetHostView.onViewDataChanged finds the target AdapterView by id
     * and calls BaseAdapter.notifyDataSetChanged() on it — it does not touch
     * sibling collection views, so updating the header preserves goal_list's
     * firstVisiblePosition automatically.
     *
     * The refreshClicks parameter is retained for source-compat with callers
     * but is now ignored: clicks live inside the header item's fillInIntents
     * which are rebuilt every getViewAt.
     */
    @Suppress("UNUSED_PARAMETER")
    fun notifyListAndHeader(context: Context, refreshClicks: Boolean = true) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, GoalsAppWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.header_list)
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.goal_list)
    }

    fun requestRefresh(context: Context) {
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("widget-refresh", ExistingWorkPolicy.REPLACE, req)
    }

    fun schedulePeriodicRefresh(context: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(WidgetRefreshWorker.K_PERIODIC to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "widget-refresh-periodic",
            ExistingPeriodicWorkPolicy.REPLACE,
            req
        )
    }

    fun cancelPeriodicRefresh(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("widget-refresh-periodic")
    }

    fun hasWidgets(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, GoalsAppWidgetProvider::class.java))
        return ids.isNotEmpty()
    }
}
