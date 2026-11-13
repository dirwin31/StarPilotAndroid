package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class CompanionClockTest {
    @Test fun advancesOnlyAfterResponseAndExpiresBeforeDriftGrows() {
        val clock = CompanionClock()
        assertTrue(clock.needsProbe("connection", 1_000_000_000))
        clock.record("connection", 10_000_000_000, 1_100_000_000)
        assertEquals(10_100_000_000L, clock.time("connection", 1_200_000_000))
        assertNull(clock.time("other connection", 1_200_000_000))
        assertNull(clock.time("connection", 1_099_999_999))
        assertNull(clock.time("connection", 61_100_000_000))
        clock.clear()
        assertTrue(clock.needsProbe("connection", 1_200_000_000))
    }
    @Test fun cachesLegacyCapabilityMissWithoutInventingServerTime() {
        val clock = CompanionClock()
        clock.record("legacy", null, 1_000_000_000)
        assertFalse(clock.needsProbe("legacy", 1_500_000_000))
        assertNull(clock.time("legacy", 1_500_000_000))
    }
}
