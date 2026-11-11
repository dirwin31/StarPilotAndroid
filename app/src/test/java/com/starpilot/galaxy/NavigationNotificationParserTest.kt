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
        assertEquals(12_000.0, nav.remainingMeters!!, .001)
        assertEquals(1200L, nav.remainingSeconds)
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
        assertEquals(2000.0, nav.remainingMeters!!, .001)
        assertEquals(480L, nav.remainingSeconds)
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
    @Test fun tripSummaryFormats() {
        val cases = listOf(
            NavigationTextParser.trip("20 min · 12 km · ETA 14:30") to (12_000.0 to 1200L),
            NavigationTextParser.trip("1 hr 5 min · 54 mi · 3:45 PM") to (86_904.576 to 3900L),
            NavigationTextParser.trip("1 day 3 hr · 1500 km") to (1_500_000.0 to 97_200L),
            NavigationTextParser.trip("20 min", subText = "12 km · ETA 14:30") to (12_000.0 to 1200L),
            NavigationTextParser.trip("20 min · ETA 14:30", subText = "12 km") to (12_000.0 to 1200L),
            NavigationTextParser.trip("", lines = listOf("12 min", "8 km")) to (8000.0 to 720L),
        )
        for ((parsed, expected) in cases) {
            assertEquals(expected.first, parsed!!.first, .001)
            assertEquals(expected.second, parsed.second)
        }
    }
    @Test fun tripSummaryNeedsOneUnambiguousDurationAndDistance() {
        assertNull(NavigationTextParser.trip("1,200 km · 14 hr"))
        assertNull(NavigationTextParser.trip("20 min · 12 km · 13 km"))
        assertNull(NavigationTextParser.trip("20 min · 25 min · 12 km"))
        assertNull(NavigationTextParser.trip("Turn left in 5 min"))
        assertNull(NavigationTextParser.trip("8 days · 9000 km"))
        assertNull(NavigationTextParser.trip("12 km"))
        assertNull(NavigationTextParser.trip("20 min"))
        assertNull(NavigationTextParser.trip("20 min", bigText = "12 km"))
    }
    @Test fun maneuverDistanceIsNeverTheTripDistance() {
        val nav = parser.parse("500 m", "Turn left onto Main St", subText = "20 min · ETA 14:30")!!
        assertEquals(500.0, nav.distanceMeters!!, .001)
        assertNull(nav.remainingMeters)
        assertNull(nav.remainingSeconds)
        val split = parser.parse("500 m · Turn left onto Main St", "", subText = "20 min · 12 km · ETA 14:30")!!
        assertEquals(500.0, split.distanceMeters!!, .001)
        assertEquals(12_000.0, split.remainingMeters!!, .001)
        assertNull(parser.parse("15 min · 8 km · ETA 13:15", "", subText = "15 min · 8 km"))
    }
    @Test fun arrival() {
        val right = parser.parse("200 m", "Your destination is on the right")!!
        assertEquals("arrive", right.maneuverType)
        assertEquals("right", right.modifier)
        assertEquals(200.0, right.distanceMeters!!, .001)
        assertEquals("left", parser.parse("Destination will be on the left", "")!!.modifier)
        for (text in listOf("You have arrived", "You’ve arrived", "Arrive at Left Bank Cafe", "Arrive at Exit 5 Diner")) {
            val nav = parser.parse(text, "8 min · 2 km · ETA 10:20")!!
            assertEquals(text, nav.instruction)
            assertEquals("arrive", nav.maneuverType)
            assertEquals("", nav.modifier)
            assertNull(nav.exitNumber)
        }
        assertEquals("left", parser.parse("Arrive at Main St on the left", "")!!.modifier)
    }
    @Test fun etaLinesAreNotArrivalInstructions() {
        for (text in listOf("Arrive at 14:30", "Arrive by 3:45 PM", "Arrival 14:30", "Arriving in 5 min", "ETA 14:30"))
            assertNull(text, parser.parse(text, ""))
        assertEquals("turn", parser.parse("200 m", "Turn right onto Arrive St")!!.maneuverType)
        assertEquals("Turn left onto Main St", parser.parse("200 m", "Arrive at 14:30", lines = listOf("Turn left onto Main St"))!!.instruction)
    }
}
