package com.goals.app.widget

import android.util.Log
import com.goals.app.data.models.AppSettings
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Server-aligned "today". Always derived from [AppSettings.timezone] when available
 *  so the widget agrees with the backend on which date the user is in.
 *  Falls back to the device time zone if settings are missing or invalid. */
object WidgetClock {
    private const val TAG = "WidgetClock"

    fun today(settings: AppSettings?): String {
        val zone = resolveZone(settings)
        return LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    fun today(snapshot: WidgetSnapshot): String =
        snapshot.today.ifEmpty { today(snapshot.settings) }

    private fun resolveZone(settings: AppSettings?): ZoneId {
        val raw = settings?.timezone
        if (raw.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid timezone '$raw'; falling back to system default. ${t.message}")
            ZoneId.systemDefault()
        }
    }
}
