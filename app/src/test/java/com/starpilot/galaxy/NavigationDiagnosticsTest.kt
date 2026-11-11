package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class NavigationDiagnosticsTest {
    @Test fun reportsPermissionsAndListenerState() {
        val diagnostics = NavigationDiagnostics { 1000 }
        assertTrue(diagnostics.details(false, false).contains("State: Off"))
        assertTrue(diagnostics.details(true, false).contains("Notification access required"))
        assertTrue(diagnostics.details(true, true).contains("Listener disconnected"))
    }
    @Test fun showsDetectedDataAndReceiverResult() {
        var now = 1000L
        val diagnostics = NavigationDiagnostics { now }
        diagnostics.listener(true)
        diagnostics.detected("googleMaps", NavigationNotificationText("200 m", "Turn right onto Main St"),
            NavigationGuidance("Turn right onto Main St", 200.0, "turn", "right", "Main St", "12B"), now)
        diagnostics.delivered("Bluetooth", true, false)
        now += 2000
        val text = diagnostics.details(true, true)
        for (expected in listOf("200.0 m", "turn right", "Main St", "12B", "Accepted by StarPilot", "Bluetooth", "2 s ago", "Title: 200 m"))
            assertTrue(expected, text.contains(expected))
        assertTrue(text.contains("enable it in Galaxy Navigation Setup"))
        diagnostics.failed("Galaxy rejected request (HTTP 404)")
        assertTrue(diagnostics.details(true, true).contains("HTTP 404"))
        assertFalse(diagnostics.details(false, true).contains("Title: 200 m"))
    }
    @Test fun showsTripTotalsAndLegacyFallback() {
        val diagnostics = NavigationDiagnostics { 1000 }
        diagnostics.listener(true)
        diagnostics.detected("googleMaps", NavigationNotificationText("200 m", "Turn right", subText = "20 min · 12 km"),
            NavigationGuidance("Turn right", 200.0, "turn", "right", remainingMeters = 12_000.0, remainingSeconds = 1200), 1000)
        val text = diagnostics.details(true, true)
        assertTrue(text.contains("Trip remaining: 12.0 km · 20 min"))
        assertTrue(text.contains("Sub text: 20 min · 12 km"))
        assertFalse(text.contains("Trip totals not sent"))
        diagnostics.tripTotalsUnsupported()
        assertTrue(diagnostics.details(true, true).contains("Trip totals not sent"))
        diagnostics.detected("googleMaps", NavigationNotificationText("200 m"), NavigationGuidance("Main St", 200.0), 1000)
        assertTrue(diagnostics.details(true, true).contains("Trip remaining: unknown"))
    }
    @Test fun ignoredIncompleteNotificationsRemainVisibleForDebugging() {
        val diagnostics = NavigationDiagnostics { 1000 }
        diagnostics.listener(true)
        diagnostics.detected("googleMaps", NavigationNotificationText("Changed Maps format", "Unknown fields"), null, 1000, selected = false)
        val text = diagnostics.details(true, true)
        assertTrue(text.contains("Title: Changed Maps format"))
        assertTrue(text.contains("Ignored: incomplete"))
        assertTrue(text.contains("Waiting for a navigation notification"))
    }
    @Test fun incompleteEndedAndExpiredAreClear() {
        var now = 1000L
        val diagnostics = NavigationDiagnostics { now }
        diagnostics.listener(true)
        diagnostics.detected("googleMaps", NavigationNotificationText("Rerouting"), null, now)
        assertTrue(diagnostics.details(true, true).contains("incomplete notification"))
        diagnostics.detected("googleMaps", NavigationNotificationText("200 m"), NavigationGuidance("Main St", 200.0), now)
        now += 90001
        assertTrue(diagnostics.details(true, true).contains("Observation expired"))
        diagnostics.cleared("Navigation notification ended")
        assertTrue(diagnostics.details(true, true).contains("Navigation notification ended"))
    }
}
