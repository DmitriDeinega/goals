package com.goals.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                if (!WidgetUpdater.hasWidgets(context)) return@launch
                val isDateLike = intent.action in setOf(
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED
                )
                if (isDateLike) {
                    // Local repaint without waiting for network — keeps the day-strip
                    // and "today" highlight correct even if offline at midnight.
                    val cache = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        WidgetProviderEntryPoint::class.java
                    ).cache()
                    cache.hydrate()
                    val newToday = WidgetClock.today(cache.snapshot().settings)
                    cache.apply { s ->
                        val oldToday = s.today
                        val newWeekStart = if (newToday.isNotEmpty()) {
                            WidgetDates.weekStartFor(newToday, s.settings?.firstDayOfWeek)
                        } else s.weekStart
                        // If the user hadn't explicitly navigated away from "today",
                        // advance the selected date too. Preserve an intentional past selection.
                        val newSelected = if (s.selectedDate == oldToday || s.selectedDate.isEmpty()) {
                            newToday
                        } else s.selectedDate
                        s.copy(
                            today = newToday,
                            selectedDate = newSelected,
                            weekStart = if (s.weekStart.isEmpty()) newWeekStart else s.weekStart
                        )
                    }
                    WidgetUpdater.notifyListAndHeader(context)
                }
                // Always also schedule a real network refresh (catches missed log_changed etc).
                WidgetUpdater.requestRefresh(context)
                WidgetUpdater.schedulePeriodicRefresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
