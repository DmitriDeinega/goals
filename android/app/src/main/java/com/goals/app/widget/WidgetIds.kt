package com.goals.app.widget

import java.util.UUID

object WidgetIds {
    // Glance reserves item IDs in [Long.MIN_VALUE, Long.MIN_VALUE/2 - 1] for internal use.
    // Mask the sign bit so IDs are always non-negative.
    fun stableItemId(goalId: String): Long =
        UUID.nameUUIDFromBytes(goalId.toByteArray()).mostSignificantBits and Long.MAX_VALUE
}
