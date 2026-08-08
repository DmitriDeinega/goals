package com.goals.app.widget

import com.goals.app.data.models.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's selected date is user-owned: only an explicit tap moves it, and
 * `weekStart` must always be the week containing it. These tests pin the derivation
 * the renderer uses, which is what makes the reported symptom — a day strip showing
 * one week while the date label reads a day from another — unrepresentable.
 */
class WidgetSelectionInvariantTest {

    private fun settings(firstDay: String = "sunday") = AppSettings(
        timezone = "Asia/Jerusalem",
        firstDayOfWeek = firstDay,
        currency = "NIS",
        startDate = null,
        appEnv = null
    )

    /** Mirrors WidgetRenderer.displayWeekStart, which is private. */
    private fun displayWeekStart(snapshot: WidgetSnapshot, selected: String): String {
        val fromSelected = WidgetDates.weekStartFor(selected, snapshot.settings?.firstDayOfWeek)
        return if (snapshot.weekStart == fromSelected) snapshot.weekStart else fromSelected
    }

    @Test
    fun `strip follows the selected date when cached weekStart is a different week`() {
        // The exact reported bug: midnight rolled into a new week, so a refresh had
        // advanced weekStart, while the selection stayed on the user's chosen day.
        val snapshot = WidgetSnapshot(
            today = "2026-05-10",       // Sunday — first day of the NEW week
            weekStart = "2026-05-10",   // advanced by the refresh
            selectedDate = "2026-05-08",// user's day, in the PREVIOUS week
            settings = settings()
        )
        // Must render the previous week, the one that actually contains 05-08.
        assertEquals("2026-05-03", displayWeekStart(snapshot, snapshot.selectedDate))
    }

    @Test
    fun `selected date always falls inside the rendered week`() {
        val firstDays = listOf("sunday", "monday")
        for (firstDay in firstDays) {
            // Walk a full week of selections against a fixed, deliberately stale cache.
            for (day in 3..16) {
                val selected = "2026-05-%02d".format(day)
                val snapshot = WidgetSnapshot(
                    today = "2026-05-10",
                    weekStart = "2026-05-10",
                    selectedDate = selected,
                    settings = settings(firstDay)
                )
                val start = java.time.LocalDate.parse(displayWeekStart(snapshot, selected))
                val sel = java.time.LocalDate.parse(selected)
                val offset = java.time.temporal.ChronoUnit.DAYS.between(start, sel)
                assertEquals(
                    "$selected must sit within the rendered week (firstDay=$firstDay)",
                    true,
                    offset in 0..6
                )
            }
        }
    }

    @Test
    fun `cached weekStart is used verbatim when it already agrees`() {
        val snapshot = WidgetSnapshot(
            today = "2026-05-13",
            weekStart = "2026-05-10",
            selectedDate = "2026-05-13",
            settings = settings()
        )
        assertEquals("2026-05-10", displayWeekStart(snapshot, snapshot.selectedDate))
    }

    @Test
    fun `monday-week selection on a Sunday belongs to the prior week`() {
        // Sunday is the LAST day of a Monday-based week — an easy off-by-one.
        val snapshot = WidgetSnapshot(
            today = "2026-05-11",
            weekStart = "2026-05-11",
            selectedDate = "2026-05-10", // Sunday
            settings = settings("monday")
        )
        assertEquals("2026-05-04", displayWeekStart(snapshot, snapshot.selectedDate))
    }
}
