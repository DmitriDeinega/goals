package com.goals.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WidgetIdsTest {

    @Test
    fun `stableItemId is deterministic for same goal id`() {
        val id = "65ab12c3def0123456789abc"
        assertEquals(WidgetIds.stableItemId(id), WidgetIds.stableItemId(id))
    }

    @Test
    fun `stableItemId is always non-negative (within Glance-safe range)`() {
        repeat(200) {
            val id = UUID.randomUUID().toString()
            val v = WidgetIds.stableItemId(id)
            assertTrue("id=$v must be >= 0", v >= 0L)
        }
    }

    @Test
    fun `stableItemId differs for different goal ids`() {
        val a = WidgetIds.stableItemId("aaaa1111bbbb2222cccc3333")
        val b = WidgetIds.stableItemId("ffff9999eeee8888dddd7777")
        assertNotEquals(a, b)
    }
}
