package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class SentryDedupeTest {
    @Test fun pushAndPollShareBoundedHistory() {
        val seen = SentryDedupe()
        assertTrue(seen.accept("push-and-poll"))
        assertFalse(seen.accept("push-and-poll"))
        repeat(100) { assertTrue(seen.accept("event-$it")) }
        assertEquals(100, seen.ids().size)
        assertTrue(seen.accept("push-and-poll"))
        assertFalse(SentryDedupe(seen.ids()).accept("push-and-poll"))
    }
}
