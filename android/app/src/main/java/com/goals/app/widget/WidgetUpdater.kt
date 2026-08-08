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

    /**
     * Fetch a specific week's goalWeeks/logs into the cache. Used by widget
     * navigation (user picked a day/week we don't have) and by the refresh
     * worker when /api/init returned a week other than the one on screen.
     *
     * Pass selectedDate to move the selection as part of the fetch, or null to
     * refresh the week's data while leaving the selection untouched.
     *
     * User navigation and background top-ups use *separate* unique-work names.
     * They previously shared one, so a background fetch could REPLACE — i.e.
     * cancel — an in-flight fetch for a week the user had just tapped, losing the
     * navigation entirely. Only a newer user tap may cancel an older user tap.
     */
    fun requestWeekFetch(context: Context, weekStart: String, selectedDate: String?) {
        val data = if (selectedDate != null) {
            androidx.work.workDataOf(
                WidgetWeekDataWorker.K_WEEK_START to weekStart,
                WidgetWeekDataWorker.K_SELECTED_DATE to selectedDate
            )
        } else {
            androidx.work.workDataOf(WidgetWeekDataWorker.K_WEEK_START to weekStart)
        }
        val req = OneTimeWorkRequestBuilder<WidgetWeekDataWorker>()
            .setInputData(data)
            .build()
        val isUserNavigation = selectedDate != null
        val name = if (isUserNavigation) WORK_WEEK_FETCH_NAV else WORK_WEEK_FETCH_BG
        // A background top-up must never displace a user's pending navigation.
        val policy = if (isUserNavigation) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, req)
    }

    private const val WORK_WEEK_FETCH_NAV = "widget-week-fetch"
    private const val WORK_WEEK_FETCH_BG = "widget-week-fetch-bg"

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
