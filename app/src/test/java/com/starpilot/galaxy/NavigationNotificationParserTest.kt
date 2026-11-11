package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class NavigationNotificationParserTest {
    private val parser = GoogleMapsNotificationParser
    @Test fun headingTowardRoadWithoutDistance() {
        val nav = parser.parse("Head toward Pine Tree Rd", "")!!
        assertEquals("continue", nav.maneuverType)
        assertEquals("Pine Tree Rd", nav.destination)
        assertNull(nav.roadName)
        assertEquals("", nav.modifier)
        assertNull(nav.distanceMeters)
    }
    @Test fun metricAndExit() {
        val nav = parser.parse("500 m · Take exit 12B onto Main St", "20 min · 12 km · ETA 14:30")!!
        assertEquals(500.0, nav.distanceMeters!!, .001)
        assertEquals("off ramp", nav.maneuverType)
        assertEquals("12B", nav.exitNumber)
        assertEquals("Main St", nav.roadName)
        assertEquals("", nav.modifier)
    }
    @Test fun customViews() {
        val nav = parser.parse("", "", viewTitle = "0,5 km", viewDescription = "Turn left onto River Road")!!
        assertEquals(500.0, nav.distanceMeters!!, .001)
        assertEquals("turn", nav.maneuverType)
        assertEquals("left", nav.modifier)
    }
    @Test fun imperialAndNarrowSpaces() {
        for ((title, expected) in listOf("1.2 mi" to 1931.2128, "200 ft" to 60.96, "40 yd" to 36.576, "500\u202fm" to 500.0)) {
            assertEquals(expected, parser.parse(title, "Turn right onto Main St")!!.distanceMeters!!, .001)
        }
    }
    @Test fun noTripDistanceSubstitution() {
        val nav = parser.parse("Turn right onto Main St", "12 min · 8 km · ETA 13:15")!!
        assertNull(nav.distanceMeters)
        assertEquals("right", nav.modifier)
    }
    @Test fun unknownRoadDirectionIsNotInvented() {
        val nav = parser.parse("400 m – Grand Avenue", "8 min · 2 km · ETA 10:20")!!
        assertEquals("Grand Avenue", nav.instruction)
        assertEquals("", nav.maneuverType)
        assertEquals("", nav.modifier)
    }
    @Test fun incompleteAndNonNavigation() {
        assertNull(parser.parse("", ""))
        assertNull(parser.parse("Google Maps", "Tell us about your visit"))
        assertNull(parser.parse("15 min · 8 km · ETA 13:15", ""))
        assertNull(parser.parse("500 m", "20 min · 12 km · ETA 14:30"))
    }
    @Test fun reroutingClearsPreviousManeuver() {
        for (text in listOf("Rerouting…", "Recalculating", "Searching for GPS", "GPS signal lost", "Navigation paused")) {
            assertNull(parser.parse(text, "Turn left onto Old Road"))
        }
    }
    @Test fun roadNamesDoNotDetermineTurnDirections() {
        assertEquals("left", parser.parse("200 m", "Turn left onto Right Bank Road")!!.modifier)
        assertEquals("", parser.parse("200 m · Left Bank Road", "10 min · 2 km · ETA 12:30")!!.modifier)
        assertNull(parser.parse("1,000 m", "Turn right onto Main St")!!.distanceMeters)
    }
    @Test fun maneuverTextWinsOverBareRoadDescription() {
        val nav = parser.parse("", "Turn right onto US-95 N", viewTitle = "0.5 mi", viewDescription = "US-95 N")!!
        assertEquals("Turn right onto US-95 N", nav.instruction)
        assertEquals("turn", nav.maneuverType)
        assertEquals("right", nav.modifier)
        assertEquals("US-95 N", nav.roadName)
        assertEquals(804.672, nav.distanceMeters!!, .001)
    }
    @Test fun laterThenStepIsNotTheCurrentManeuver() {
        val nav = parser.parse("0.5 mi", "US-95 N", lines = listOf("Then turn left onto Main St"))!!
        assertEquals("US-95 N", nav.instruction)
        assertEquals("", nav.maneuverType)
        assertEquals("", nav.modifier)
    }
    @Test fun knownModifiers() {
        assertEquals("sharpLeft", parser.parse("200 m", "Make a sharp left onto Main St")!!.modifier)
        assertEquals("slightRight", parser.parse("200 m", "Keep right at the fork")!!.modifier)
        assertEquals("uturn", parser.parse("200 m", "Make a U-turn")!!.modifier)
    }
}
