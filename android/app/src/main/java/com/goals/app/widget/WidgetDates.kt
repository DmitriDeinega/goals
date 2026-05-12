package com.goals.app.widget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object WidgetDates {
    private val FMT = DateTimeFormatter.ISO_LOCAL_DATE

    fun weekStartFor(date: String, firstDayOfWeek: String?): String {
        val d = LocalDate.parse(date, FMT)
        val first = if (firstDayOfWeek == "monday") DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        val daysSince = (d.dayOfWeek.value - first.value + 7) % 7
        return d.minusDays(daysSince.toLong()).format(FMT)
    }

    fun weekEndFor(weekStart: String): String =
        LocalDate.parse(weekStart, FMT).plusDays(6).format(FMT)

    /** Backward chevron: jump to last day (Sat/Sun) of the previous week.
     *  Returns null if the previous week is fully before [startDate]. */
    fun prevSelectedDate(currentSelected: String, firstDayOfWeek: String?, startDate: String?): String? {
        val curStart = weekStartFor(currentSelected, firstDayOfWeek)
        val prevStart = LocalDate.parse(curStart, FMT).minusDays(7)
        val prevEnd = prevStart.plusDays(6)
        if (startDate != null) {
            val min = LocalDate.parse(startDate, FMT)
            if (prevEnd.isBefore(min)) return null
            // Clamp: if min is in the prev week, land on min instead of weekEnd.
            if (min.isAfter(prevStart)) return min.format(FMT)
        }
        return prevEnd.format(FMT)
    }

    /** Forward chevron: jump to next week. If today is in next week → today,
     *  else if next week is fully past → last day of next week. Returns null
     *  if next week is fully in the future. */
    fun nextSelectedDate(currentSelected: String, today: String, firstDayOfWeek: String?): String? {
        val curStart = weekStartFor(currentSelected, firstDayOfWeek)
        val nextStart = LocalDate.parse(curStart, FMT).plusDays(7)
        val nextEnd = nextStart.plusDays(6)
        val todayDate = LocalDate.parse(today, FMT)
        return when {
            todayDate < nextStart -> null
            todayDate in nextStart..nextEnd -> today
            else -> nextEnd.format(FMT)
        }
    }

    fun formatRange(weekStart: String): String {
        val s = LocalDate.parse(weekStart, FMT)
        val e = s.plusDays(6)
        val monthFmt = DateTimeFormatter.ofPattern("MMM")
        return if (s.month == e.month) {
            "${s.format(monthFmt)} ${s.dayOfMonth} - ${e.dayOfMonth}"
        } else {
            "${s.format(monthFmt)} ${s.dayOfMonth} - ${e.format(monthFmt)} ${e.dayOfMonth}"
        }
    }

    fun formatDayLabel(date: String): String {
        val d = LocalDate.parse(date, FMT)
        return d.format(DateTimeFormatter.ofPattern("EEE MMM d"))
    }
}
