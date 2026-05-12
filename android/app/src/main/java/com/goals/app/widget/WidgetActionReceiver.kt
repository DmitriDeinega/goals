package com.goals.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.goals.app.MainActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

private const val TAG = "WidgetActionReceiver"

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != INTENT_ACTION) return
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_TOGGLE -> handleToggle(context, intent)
            ACTION_NAV_PREV -> handleNav(context, prev = true)
            ACTION_NAV_NEXT -> handleNav(context, prev = false)
            ACTION_NAV_DAY -> handleNavDay(context, intent)
            ACTION_LAUNCH_APP -> handleLaunchApp(context)
        }
    }

    private fun handleToggle(context: Context, intent: Intent) {
        val goalId = intent.getStringExtra(EXTRA_GOAL_ID) ?: return
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        if (slotIndex < 0) return

        val pendingResult = goAsync()
        try {
            val cache = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetProviderEntryPoint::class.java
            ).cache()
            runBlocking { cache.hydrate() }
            val snapshot = cache.snapshot()
            val log = snapshot.logs.find { it.goalId == goalId && it.date == date }
            val isNegative = snapshot.goalWeeks.find { it.goalId == goalId }?.snapshot?.isNegative
                ?: snapshot.goals.find { it.id == goalId }?.isNegative
                ?: false
            val currentValue = log?.slots?.getOrNull(slotIndex) ?: isNegative
            val desired = !currentValue

            // Mark slot as in-flight for the dim visual. Re-marking is idempotent;
            // any previous in-flight key for the same slot stays set until the
            // worker clears it in its terminal branch (success or final-attempt failure).
            cache.markToggling(goalId, date, slotIndex)
            WidgetUpdater.notifyListAndHeader(context, refreshClicks = false)

            val data = workDataOf(
                WidgetToggleWorker.K_GOAL to goalId,
                WidgetToggleWorker.K_DATE to date,
                WidgetToggleWorker.K_SLOT to slotIndex,
                WidgetToggleWorker.K_DESIRED to desired
            )
            val req = OneTimeWorkRequestBuilder<WidgetToggleWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "toggle-$goalId-$date-$slotIndex",
                ExistingWorkPolicy.REPLACE,
                req
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Toggle error: ${t.message}")
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleNav(context: Context, prev: Boolean) {
        val pendingResult = goAsync()
        try {
            val cache = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetProviderEntryPoint::class.java
            ).cache()
            runBlocking { cache.hydrate() }
            val snapshot = cache.snapshot()
            val today = WidgetClock.today(snapshot)
            val current = snapshot.selectedDate.ifEmpty { today }
            val firstDay = snapshot.settings?.firstDayOfWeek
            val newDate = if (prev) {
                WidgetDates.prevSelectedDate(current, firstDay, snapshot.settings?.startDate) ?: return
            } else {
                WidgetDates.nextSelectedDate(current, today, firstDay) ?: return
            }
            val newWeekStart = WidgetDates.weekStartFor(newDate, firstDay)
            if (newWeekStart != snapshot.weekStart) {
                enqueueWeekFetch(context, newWeekStart, newDate)
            } else {
                cache.apply { s -> s.copy(selectedDate = newDate) }
                WidgetUpdater.notifyListAndHeader(context, refreshClicks = false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Nav error: ${t.message}")
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleNavDay(context: Context, intent: Intent) {
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        val pendingResult = goAsync()
        try {
            val cache = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetProviderEntryPoint::class.java
            ).cache()
            runBlocking { cache.hydrate() }
            val snapshot = cache.snapshot()
            val firstDay = snapshot.settings?.firstDayOfWeek
            val newWeekStart = WidgetDates.weekStartFor(date, firstDay)
            if (newWeekStart != snapshot.weekStart) {
                enqueueWeekFetch(context, newWeekStart, date)
            } else {
                cache.apply { s -> s.copy(selectedDate = date) }
                WidgetUpdater.notifyListAndHeader(context, refreshClicks = false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NavDay error: ${t.message}")
        } finally {
            pendingResult.finish()
        }
    }

    private fun enqueueWeekFetch(context: Context, newWeekStart: String, newSelectedDate: String) {
        val req = OneTimeWorkRequestBuilder<WidgetWeekDataWorker>()
            .setInputData(
                workDataOf(
                    WidgetWeekDataWorker.K_WEEK_START to newWeekStart,
                    WidgetWeekDataWorker.K_SELECTED_DATE to newSelectedDate
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget-week-fetch",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun handleLaunchApp(context: Context) {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "Launch error: ${t.message}")
        }
    }

    companion object {
        const val INTENT_ACTION = "com.goals.app.widget.ACTION"
        const val EXTRA_ACTION = "x_action"
        const val EXTRA_GOAL_ID = "goal_id"
        const val EXTRA_DATE = "date"
        const val EXTRA_SLOT_INDEX = "slot_index"

        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NAV_PREV = "nav_prev"
        const val ACTION_NAV_NEXT = "nav_next"
        const val ACTION_NAV_DAY = "nav_day"
        const val ACTION_LAUNCH_APP = "launch_app"
    }
}
