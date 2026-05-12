package com.goals.app.data

import com.goals.app.data.models.GoalChangedPayload
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoalChangedPayloadParseTest {

    private val gson = Gson()

    /** Locks in the schema fix: `reordered_goals` arrives as
     *  `[{goal_id, new_order}]` from the server, parsed into `List<ReorderItem>`. */
    @Test
    fun `deleted action with reordered_goals parses into ReorderItem entries`() {
        val json = """
            {
                "action": "deleted",
                "goal": null,
                "goal_id": "65ab12c3def0123456789abc",
                "goal_week": null,
                "logs": null,
                "reordered_goals": [
                    {"goal_id": "65ab10000000000000000000", "new_order": 0},
                    {"goal_id": "65ab11000000000000000000", "new_order": 1}
                ],
                "seq": 42
            }
        """.trimIndent()

        val payload = gson.fromJson(json, GoalChangedPayload::class.java)
        assertNotNull(payload)
        assertEquals("deleted", payload.action)
        assertEquals("65ab12c3def0123456789abc", payload.goalId)
        assertEquals(42L, payload.seq)

        val reordered = payload.reorderedGoals!!
        assertEquals(2, reordered.size)
        assertEquals("65ab10000000000000000000", reordered[0].goalId)
        assertEquals(0, reordered[0].newOrder)
        assertEquals("65ab11000000000000000000", reordered[1].goalId)
        assertEquals(1, reordered[1].newOrder)
    }

    @Test
    fun `created action with goal parses without reordered_goals`() {
        val json = """
            {
                "action": "created",
                "goal": {
                    "id": "65ab12c3def0123456789abc",
                    "name": "Read",
                    "type": "daily",
                    "is_negative": false,
                    "times_per_day": 1,
                    "times_per_week": null,
                    "reward_rules": [],
                    "order": 0,
                    "version": 0
                },
                "goal_id": null,
                "goal_week": null,
                "logs": null,
                "reordered_goals": null,
                "seq": 7
            }
        """.trimIndent()

        val payload = gson.fromJson(json, GoalChangedPayload::class.java)
        assertEquals("created", payload.action)
        assertEquals("Read", payload.goal?.name)
        assertEquals(1, payload.goal?.timesPerDay)
        assertNull(payload.reorderedGoals)
    }
}
