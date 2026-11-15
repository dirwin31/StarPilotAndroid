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
        val duty = PhoneGpsDuty(holdMs = 60_000, probeEveryMs = 120_000)
        assertFalse(duty.wanted(0, linked = false))
        // A sign StarPilot may be near turns the receiver on for a while, and each driving answer keeps it on.
        duty.wake(1_000)
        assertTrue(duty.wanted(60_999, linked = false))
        assertFalse(duty.wanted(61_000, linked = false))
        duty.answered(70_000, driving = true)
        assertTrue(duty.wanted(129_999, linked = false))
        assertFalse(duty.wanted(130_000, linked = false))
        // A parked comma turns it off at once, even after a fresh wake.
        duty.wake(200_000)
        duty.answered(201_000, driving = false)
        assertFalse(duty.wanted(202_000, linked = true))
        // While it stays reachable it is asked again every two minutes, for one hold at most.
        assertTrue(duty.wanted(321_000, linked = true))
        assertTrue(duty.wanted(380_999, linked = true))
        assertFalse(duty.wanted(381_000, linked = true))
        assertFalse(duty.wanted(440_000, linked = false))
        assertTrue(duty.wanted(441_000, linked = true))
    }
}
