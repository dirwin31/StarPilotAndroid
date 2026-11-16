package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class PhoneGpsTest {
    private val fix = PhoneGps.Fix(40.0, -110.0, 5f, 1_790_000_000_000, altitude = 1200.0, speed = 20f, bearing = 90f, bearingAccuracy = 10f)

    @Test fun sendsKnownValues() {
        val payload = PhoneGps.payload(fix, 300)!!
        assertEquals(40.0, payload["latitude"]); assertEquals(300L, payload["ageMs"]); assertEquals(90.0, payload["bearing"])
        assertFalse("speedAccuracy" in payload)
    }

    @Test fun dropsBearingWithoutAccuracy() {
        val payload = PhoneGps.payload(fix.copy(bearingAccuracy = null), 300)!!
        assertFalse("bearing" in payload)
    }

    @Test fun rejectsOldOrUnusableFixes() {
        assertNull(PhoneGps.payload(fix, 2500))
        assertNull(PhoneGps.payload(fix, -1))
        assertNull(PhoneGps.payload(fix.copy(accuracy = 0f), 300))
        assertNull(PhoneGps.payload(fix.copy(latitude = Double.NaN), 300))
    }
    @Test fun rejectsInvalidCoordinatesAndInfiniteAccuracy() {
        assertNull(PhoneGps.payload(fix.copy(latitude = 90.01), 0))
        assertNull(PhoneGps.payload(fix.copy(longitude = -180.01), 0))
        assertNull(PhoneGps.payload(fix.copy(accuracy = Float.POSITIVE_INFINITY), 0))
    }

    @Test fun keepsNewestMeasurementAcrossBlockedTransportAndRetries() {
        val stream = PhoneGpsStream("test-session")
        assertTrue(stream.offer(fix, 1_000_000_000, 1_000_000_000))
        assertTrue(stream.offer(fix.copy(latitude = 41.0), 1_500_000_000, 1_500_000_000))
        assertFalse(stream.offer(fix, 1_000_000_000, 1_600_000_000))
        assertFalse(stream.offer(fix, 1_500_000_000, 1_600_000_000))
        val (sequence, payload) = stream.payload(2_000_000_000, serverMonoTimeNs = 12_000_000_000)!!
        assertEquals(2L, sequence)
        assertEquals(41.0, payload["latitude"])
        assertEquals(500L, payload["ageMs"])
        assertEquals("test-session", payload["sessionId"])
        assertEquals(1_500_000_000L, payload["sampleElapsedRealtimeNanos"])
        assertEquals(2_000_000_000L, payload["sentElapsedRealtimeNanos"])
        assertEquals(12_000_000_000L, payload["serverMonoTimeNs"])
        assertEquals(2L, stream.payload(2_100_000_000)!!.first)
        assertNull(stream.payload(3_501_000_000))
    }

    @Test fun futureFixCannotDisplaceFreshMeasurementAndLegacyOmitsMetadata() {
        val stream = PhoneGpsStream("test-session")
        assertFalse(stream.offer(fix, 1_000_000_001, 1_000_000_000))
        assertTrue(stream.offer(fix, 1_000_000_000, 1_000_000_000))
        assertFalse(stream.offer(fix.copy(latitude = Double.NaN), 1_500_000_000, 1_500_000_000))
        assertEquals(PhoneGps.payload(fix, 500), stream.payload(1_500_000_000, extended = false)!!.second)
    }

    @Test fun receiverRunsOnlyWhileStarPilotIsNearAndDriving() {
        val duty = PhoneGpsDuty(holdMs = 60_000)
        assertFalse(duty.wanted(0))
        // A sign StarPilot may be near turns the receiver on for a while, and each driving answer keeps it on.
        duty.wake(1_000)
        assertTrue(duty.wanted(60_999))
        assertFalse(duty.wanted(61_000))
        duty.answered(70_000, driving = true)
        assertTrue(duty.wanted(129_999))
        assertFalse(duty.wanted(130_000))
        // A parked comma turns it off at once, even after a fresh wake.
        duty.wake(200_000)
        duty.answered(201_000, driving = false)
        assertFalse(duty.wanted(202_000))
    }

    @Test fun restingStatusCheckFindsTheDriveWithinTenSecondsWithoutGps() {
        val duty = PhoneGpsDuty(holdMs = 60_000, checkEveryMs = 10_000)
        duty.answered(0, driving = false)
        assertFalse(duty.checkDue(9_999, linked = true))
        assertTrue(duty.checkDue(10_000, linked = true))
        assertFalse(duty.checkDue(10_001, linked = true))
        duty.answered(10_500, driving = false)
        assertFalse(duty.wanted(10_500))
        // The ignition came on at 12 s; the next check sees it and the receiver turns on.
        assertTrue(duty.checkDue(20_500, linked = true))
        duty.answered(20_600, driving = true)
        assertTrue(duty.wanted(20_600))
        // Fixes answered once a second need no extra checks.
        for (now in 21_000L..60_000L step 1_000L) duty.answered(now, driving = true)
        assertFalse(duty.checkDue(60_500, linked = true))
    }

    @Test fun drivingStatusKeepsReceiverOnWhileNoFixArrives() {
        val duty = PhoneGpsDuty(holdMs = 60_000, checkEveryMs = 10_000)
        duty.answered(0, driving = true)
        // Tunnel or cold start: no fix is posted, but checks keep confirming the drive.
        for (now in 10_000L..300_000L step 10_000L) {
            assertTrue(duty.checkDue(now, linked = true))
            duty.answered(now, driving = true)
            assertTrue(duty.wanted(now + 9_999))
        }
    }

    @Test fun linkLostMidDriveKeepsSearchingAndResumes() {
        val duty = PhoneGpsDuty(holdMs = 60_000, searchEveryMs = 30_000, idleSearchEveryMs = 180_000, searchForMs = 1_800_000)
        duty.answered(0, driving = true)
        // Posts fail from here; the receiver rests after the hold, but unlinked checks keep trying to reconnect.
        assertFalse(duty.wanted(60_000))
        assertTrue(duty.checkDue(60_000, linked = false))
        assertFalse(duty.checkDue(89_999, linked = false))
        assertTrue(duty.checkDue(90_000, linked = false))
        duty.answered(91_000, driving = true)
        assertTrue(duty.wanted(91_000))
    }

    @Test fun unlinkedSearchBacksOffWhenStarPilotStaysAway() {
        val duty = PhoneGpsDuty(searchEveryMs = 30_000, idleSearchEveryMs = 180_000, searchForMs = 1_800_000)
        duty.answered(0, driving = false)
        assertTrue(duty.checkDue(1_770_000, linked = false))
        assertFalse(duty.checkDue(1_830_000, linked = false))
        assertTrue(duty.checkDue(1_950_000, linked = false))
        // A wake (Bluetooth, Wi-Fi, app, Maps) restores the fast search.
        duty.wake(2_000_000)
        assertTrue(duty.checkDue(2_000_000, linked = false))
        assertTrue(duty.checkDue(2_030_000, linked = false))
    }

    @Test fun savedLanHostOnOtherWifiBacksOffUntilCommaAnswers() {
        val duty = PhoneGpsDuty(checkEveryMs = 10_000, searchEveryMs = 30_000, idleSearchEveryMs = 180_000, searchForMs = 1_800_000)
        duty.answered(0, driving = false)
        assertTrue(duty.checkDue(10_000, linked = true))
        duty.unanswered(14_000)
        assertFalse(duty.checkDue(20_000, linked = true))
        assertTrue(duty.checkDue(40_000, linked = true))
        duty.unanswered(44_000)
        // Away for over half an hour: one attempt every three minutes.
        assertTrue(duty.checkDue(1_830_000, linked = true))
        assertFalse(duty.checkDue(1_900_000, linked = true))
        assertTrue(duty.checkDue(2_010_000, linked = true))
        // An answer restores the ten-second check.
        duty.answered(2_011_000, driving = false)
        assertTrue(duty.checkDue(2_021_000, linked = true))
    }

    @Test fun wakeAsksCommaOnTheNextTick() {
        val duty = PhoneGpsDuty(checkEveryMs = 10_000, searchEveryMs = 30_000)
        duty.answered(0, driving = false)
        assertTrue(duty.checkDue(30_000, linked = false))
        duty.unanswered(30_000)
        assertFalse(duty.checkDue(32_000, linked = true))
        duty.wake(32_000)
        assertTrue(duty.wanted(32_000))
        assertTrue(duty.checkDue(32_000, linked = true))
    }

    @Test fun olderStarPilotWithoutDriveStateFallsBackToGpsProbe() {
        val duty = PhoneGpsDuty(holdMs = 60_000, checkEveryMs = 10_000, probeEveryMs = 120_000)
        duty.answered(0, driving = false)
        assertTrue(duty.checkDue(10_000, linked = true))
        duty.statusUnsupported(10_000)
        assertTrue(duty.wanted(10_000))
        duty.answered(15_000, driving = false)
        assertFalse(duty.wanted(15_000))
        // Further unsupported replies probe with the receiver only every two minutes.
        duty.statusUnsupported(25_000)
        assertFalse(duty.wanted(25_000))
        duty.statusUnsupported(130_000)
        assertTrue(duty.wanted(130_000))
    }
}
