package link.firestar.companion

import org.junit.Assert.*
import org.junit.Test

class RoutePlannerTest {
    @Test fun fallbackAndUpgradeOrder() {
        assertEquals(Route.HOME_NETWORK, RoutePlanner.next(null, "auto", true, true, true, true))
        assertEquals(Route.TUNNEL, RoutePlanner.next(Route.GALAXY_WIFI, "auto", true, true, true, true))
        assertEquals(Route.BLUETOOTH, RoutePlanner.next(Route.TUNNEL, "auto", true, true, true, true))
        assertNull(RoutePlanner.next(Route.BLUETOOTH, "auto", true, true, true, true))
        assertTrue(RoutePlanner.better(Route.HOME_NETWORK, Route.TUNNEL))
        assertTrue(RoutePlanner.better(Route.TUNNEL, Route.BLUETOOTH))
        assertFalse(RoutePlanner.better(Route.BLUETOOTH, Route.TUNNEL))
    }
    @Test fun manualModesNeverFallback() {
        for (mode in listOf("wifi", "remote", "bluetooth")) for (route in Route.entries)
            assertNull(RoutePlanner.next(route, mode, true, true, true, true))
        assertEquals(Route.TUNNEL, RoutePlanner.next(null, "remote", true, true, true, true))
        assertEquals(Route.BLUETOOTH, RoutePlanner.next(null, "auto", false, false, true, true))
        assertNull(RoutePlanner.next(null, "auto", false, true, false, false))
    }
}
