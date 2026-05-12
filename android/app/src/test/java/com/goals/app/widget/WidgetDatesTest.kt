package com.goals.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetDatesTest {

    // ── weekStartFor ─────────────────────────────────────────────────────────

    @Test
    fun `weekStartFor sunday-week, date is a Wednesday`() {
        // 2026-05-13 is a Wednesday; Sunday-based week starts on 2026-05-10 (Sun).
        assertEquals("2026-05-10", WidgetDates.weekStartFor("2026-05-13", "sunday"))
    }

    @Test
    fun `weekStartFor sunday-week, date is Sunday returns same date`() {
        assertEquals("2026-05-10", WidgetDates.weekStartFor("2026-05-10", "sunday"))
    }

    @Test
    fun `weekStartFor sunday-week, date is Saturday returns prior Sunday`() {
        assertEquals("2026-05-10", WidgetDates.weekStartFor("2026-05-16", "sunday"))
    }

    @Test
    fun `weekStartFor monday-week, date is Wednesday`() {
        // 2026-05-13 Wed; Monday-based week starts on 2026-05-11 (Mon).
        assertEquals("2026-05-11", WidgetDates.weekStartFor("2026-05-13", "monday"))
    }

    @Test
    fun `weekStartFor monday-week, date is Sunday returns prior Monday`() {
        assertEquals("2026-05-04", WidgetDates.weekStartFor("2026-05-10", "monday"))
    }

    @Test
    fun `weekStartFor null firstDayOfWeek defaults to Sunday`() {
        assertEquals("2026-05-10", WidgetDates.weekStartFor("2026-05-13", null))
    }

    @Test
    fun `weekEndFor returns weekStart plus six days`() {
        assertEquals("2026-05-16", WidgetDates.weekEndFor("2026-05-10"))
    }

    // ── prevSelectedDate ─────────────────────────────────────────────────────

    @Test
    fun `prevSelectedDate without startDate returns Saturday of previous Sunday-week`() {
        // Selected 2026-05-13 (Wed of week 2026-05-10..16); prev week 2026-05-03..09; Sat = 2026-05-09.
        assertEquals("2026-05-09", WidgetDates.prevSelectedDate("2026-05-13", "sunday", null))
    }

    @Test
    fun `prevSelectedDate monday-week returns Sunday of previous week`() {
        // Selected 2026-05-13 Wed; monday-week 05-11..17; prev week 05-04..10; last day = Sun 05-10.
        assertEquals("2026-05-10", WidgetDates.prevSelectedDate("2026-05-13", "monday", null))
    }

    @Test
    fun `prevSelectedDate returns null when prev week is fully before startDate`() {
        // Selected 2026-05-13; prev week ends 2026-05-09; startDate 2026-05-10 → null.
        assertNull(WidgetDates.prevSelectedDate("2026-05-13", "sunday", "2026-05-10"))
    }

    @Test
    fun `prevSelectedDate clamps to startDate when startDate falls in prev week`() {
        // Selected 2026-05-13; prev week 05-03..09; startDate 2026-05-06 → clamp to 2026-05-06.
        assertEquals("2026-05-06", WidgetDates.prevSelectedDate("2026-05-13", "sunday", "2026-05-06"))
    }

    @Test
    fun `prevSelectedDate ignores startDate when startDate is at or before prev weekStart`() {
        // startDate 2026-05-03 == prev weekStart; should return weekEnd, not clamp.
        assertEquals("2026-05-09", WidgetDates.prevSelectedDate("2026-05-13", "sunday", "2026-05-03"))
    }

    // ── nextSelectedDate ─────────────────────────────────────────────────────

    @Test
    fun `nextSelectedDate returns today when today is in next week`() {
        // Selected 2026-05-09 Sat (week 05-03..09); next week 05-10..16; today 2026-05-13 in next week.
        assertEquals("2026-05-13", WidgetDates.nextSelectedDate("2026-05-09", "2026-05-13", "sunday"))
    }

    @Test
    fun `nextSelectedDate returns next-week-end when next week is fully past`() {
        // Selected 2026-04-04 (week 03-29..04-04); next week 04-05..11; today 2026-05-13 (past).
        assertEquals("2026-04-11", WidgetDates.nextSelectedDate("2026-04-04", "2026-05-13", "sunday"))
    }

    @Test
    fun `nextSelectedDate returns null when next week is fully in the future`() {
        // Selected 2026-05-13 (week 05-10..16); next week 05-17..23; today 2026-05-14 → null.
        assertNull(WidgetDates.nextSelectedDate("2026-05-13", "2026-05-14", "sunday"))
    }

    // ── formatRange ─────────────────────────────────────────────────────────

    @Test
    fun `formatRange same-month`() {
        assertEquals("May 10 - 16", WidgetDates.formatRange("2026-05-10"))
    }

    @Test
    fun `formatRange cross-month`() {
        // 2026-04-26 (Sun) .. 2026-05-02 (Sat)
        assertEquals("Apr 26 - May 2", WidgetDates.formatRange("2026-04-26"))
    }

    @Test
    fun `formatDayLabel returns Day Mon n`() {
        // 2026-05-11 is a Monday.
        assertEquals("Mon May 11", WidgetDates.formatDayLabel("2026-05-11"))
    }
}
