package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class NavigationNotificationProvidersTest {
    @Test fun onlyRegisteredAppsAreEnabledByDefault() {
        val registry = NavigationNotificationParsers.DEFAULT
        assertSame(GoogleMapsNotificationParser, registry.forPackage("com.google.android.apps.maps"))
        assertEquals("waze", registry.forPackage("com.waze")!!.source)
        assertNull(registry.forPackage("com.google.android.apps.maps.fake"))
    }
    @Test fun googleMapsUsesTheSharedContract() {
        val parser = NavigationNotificationParsers.DEFAULT.forPackage("com.google.android.apps.maps")!!
        val nav = parser.parse(NavigationNotificationText("200 m", "Turn right onto Main St"))!!
        assertEquals("googleMaps", parser.source)
        assertEquals(200.0, nav.distanceMeters!!, .001)
        assertEquals("right", nav.modifier)
    }
    @Test fun newAdapterNeedsNoTransportOrLifecycleImplementation() {
        // A test-only adapter proves extensibility; this does not claim Waze support.
        val fixtureAdapter = object : NavigationNotificationParser {
            override val source = "fixture"
            override val packageName = "test.navigation"
            override fun parse(text: NavigationNotificationText) = NavigationGuidance(text.text, null)
        }
        val registry = NavigationNotificationParsers(listOf(GoogleMapsNotificationParser, fixtureAdapter))
        assertEquals("Fixture road", registry.forPackage("test.navigation")!!
            .parse(NavigationNotificationText(text = "Fixture road"))!!.instruction)
        assertTrue(registry.priority("googleMaps") < registry.priority("fixture"))
        assertEquals(Int.MAX_VALUE, registry.priority("unknown"))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateProvidersAreRejected() {
        NavigationNotificationParsers(listOf(GoogleMapsNotificationParser, GoogleMapsNotificationParser))
    }
    @Test fun standardTextProviderUsesSharedManeuverGrammar() {
        val parser = NavigationNotificationParsers.DEFAULT.forPackage("com.waze")!!
        val cases = listOf(
            Triple("Turn left onto Main St", "turn", "left"),
            Triple("Turn right onto Main St", "turn", "right"),
            Triple("Take exit 12B on the right", "off ramp", "right"),
            Triple("Take the left ramp toward Airport", "on ramp", "left"),
            Triple("Keep left at the fork", "fork", "slightLeft"),
            Triple("Keep right at the fork", "fork", "slightRight"),
            Triple("Merge right onto I-80", "merge", "right"),
            Triple("At the roundabout take the third exit", "roundabout", ""),
            Triple("Continue straight onto Main St", "continue", "straight"),
            Triple("Go straight ahead", "continue", "straight"),
        )
        for ((text, kind, side) in cases) {
            val nav = parser.parse(NavigationNotificationText("200 m", text))!!
            assertEquals(text, nav.instruction)
            assertEquals(kind, nav.maneuverType)
            assertEquals(side, nav.modifier)
            assertEquals(200.0, nav.distanceMeters!!, .001)
        }
    }
    @Test fun roundaboutExitNumbersAndDestinationArePreserved() {
        val parser = NavigationNotificationParsers.DEFAULT.forPackage("com.waze")!!
        for (exit in listOf("third", "3rd", "3")) {
            val nav = parser.parse(NavigationNotificationText("200 m", "At the roundabout take the $exit exit toward Airport"))!!
            assertEquals("3", nav.exitNumber)
            assertEquals("Airport", nav.destination)
            assertNull(nav.roadName)
        }
        assertNull(parser.parse(NavigationNotificationText("200 m", "At the roundabout head toward Third Exit"))!!.exitNumber)
    }
    @Test fun incompleteStandardTextIsRejected() {
        val parser = NavigationNotificationParsers.DEFAULT.forPackage("com.waze")!!
        for (text in listOf(
            NavigationNotificationText("200 m", "Main Street"),
            NavigationNotificationText("Turn right", ""),
            NavigationNotificationText("200 m", "Rerouting"),
            NavigationNotificationText("200 m", "Tournez à droite"),
            NavigationNotificationText("", ""),
        )) assertNull(parser.parse(text))
    }

}
