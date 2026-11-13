package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class NavigationObservationOrderTest {
    @Test fun removedNotificationCannotBeReplayedButNewerReplacementCan() {
        val order = NavigationObservationOrder()
        assertTrue(order.accepts("maps", 100))
        order.removed("maps", 200, 0)
        order.removed("maps", 100, 0)
        assertFalse(order.accepts("maps", 100))
        assertFalse(order.accepts("maps", 200))
        assertTrue(order.accepts("maps", 201))
        assertTrue(order.accepts("waze", 100))
    }
}
