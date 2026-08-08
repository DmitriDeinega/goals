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
                        // Advance `today` only. The selected date is user-owned — a day
                        // rollover must never move it, not even when the user happened to
                        // be sitting on the old today. The new day simply becomes
                        // tappable and the TODAY button lights up as the affordance.
                        val newWeekStart = if (s.weekStart.isEmpty() && newToday.isNotEmpty()) {
                            WidgetDates.weekStartFor(newToday, s.settings?.firstDayOfWeek)
                        } else s.weekStart
                        s.copy(
                            today = newToday,
                            selectedDate = s.selectedDate.ifEmpty { newToday },
                            weekStart = newWeekStart
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
