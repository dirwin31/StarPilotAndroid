package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class PreferredWifiTest {
    private val names = setOf("Home", "Garage")

    @Test fun cellularAlwaysSkipsLan() {
        for (enabled in listOf(false, true)) {
            assertFalse(PreferredWifi.allows(false, false, enabled, null, names))
            assertEquals(Route.TUNNEL, RoutePlanner.next(null, "auto", false, true, true, true))
            assertEquals(Route.BLUETOOTH, RoutePlanner.next(null, "auto", false, false, true, true))
        }
    }

    @Test fun preferredNamesAreExactAndUnknownFailsClosedOnlyWhenEnabled() {
        assertTrue(PreferredWifi.allows(true, false, false, null, emptySet()))
        for (name in listOf("Home", "Garage")) assertTrue(PreferredWifi.allows(true, false, true, name, names))
        for (name in listOf(null, "Cafe", "home", "Home ")) assertFalse(PreferredWifi.allows(true, false, true, name, names))
        assertFalse(PreferredWifi.allows(true, false, true, "Home", emptySet()))
        assertTrue(PreferredWifi.allows(true, true, true, null, emptySet()))
    }

    @Test fun skippedLanPreservesEveryRouteOrderAndMissingCredentials() {
        for (order in listOf("wifi,remote,bluetooth", "remote,wifi,bluetooth", "remote,bluetooth,wifi")) {
            val parsed = RoutePlanner.parse(order)
            assertEquals(Route.TUNNEL, RoutePlanner.next(null, "auto", false, true, true, true, parsed))
            assertEquals(Route.BLUETOOTH, RoutePlanner.next(Route.TUNNEL, "auto", false, true, true, true, parsed))
            assertEquals(Route.BLUETOOTH, RoutePlanner.next(null, "auto", false, true, false, true, parsed))
        }
        for (order in listOf("wifi,bluetooth,remote", "bluetooth,wifi,remote", "bluetooth,remote,wifi")) {
            val parsed = RoutePlanner.parse(order)
            assertEquals(Route.BLUETOOTH, RoutePlanner.next(null, "auto", false, true, true, true, parsed))
            assertEquals(Route.TUNNEL, RoutePlanner.next(Route.BLUETOOTH, "auto", false, true, true, true, parsed))
            assertEquals(Route.TUNNEL, RoutePlanner.next(null, "auto", false, true, true, false, parsed))
        }
        assertNull(RoutePlanner.next(null, "auto", false, true, false, false))
    }

    @Test fun ssidNormalizationPreservesSpacesAndCase() {
        assertEquals("Home", PreferredWifi.normalize("\"Home\""))
        assertEquals(" Home ", PreferredWifi.normalize("\" Home \""))
        assertNull(PreferredWifi.normalize("<unknown ssid>"))
        assertNull(PreferredWifi.normalize("\"\""))
        assertNull(PreferredWifi.normalize(null))
    }
}
