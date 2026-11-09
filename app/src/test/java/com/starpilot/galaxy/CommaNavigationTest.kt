package com.starpilot.galaxy

import com.starpilot.galaxy.CommaNavigation.State
import org.junit.Assert.*
import org.junit.Test

class CommaNavigationTest {
    private val place = SharedDestination.Place("Bellagio \"Hotel\"", 36.1126, -115.1767)

    private class Fake(val statuses: MutableList<State>, val replies: MutableList<State>) : CommaNavigation.Api {
        val sent = mutableListOf<String>()
        override fun status() = statuses.removeAt(0)
        override fun action(body: String): State { sent += body; return replies.removeAt(0) }
    }

    @Test fun turnsNavigationOnThenStartsTheRoute() {
        val api = Fake(mutableListOf(State(200, "r1", enabled = false, hasKey = true)),
            mutableListOf(State(200, "r2", enabled = true, hasKey = true), State(200, "r3", true, true, 36.1126, -115.1767)))
        CommaNavigation.start(api, place)
        assertEquals("""{"action":"configure","revision":"r1","patch":{"enabled":true}}""", api.sent[0])
        assertEquals("""{"action":"select","revision":"r2","destination":{"name":"Bellagio \"Hotel\"","latitude":36.1126,"longitude":-115.1767}}""", api.sent[1])
    }

    @Test fun skipsWhenAlreadyRoutingThere() {
        val api = Fake(mutableListOf(State(200, "r1", true, true, 36.1126, -115.1767)), mutableListOf())
        CommaNavigation.start(api, place)
        assertTrue(api.sent.isEmpty())
    }

    @Test fun refusesWithoutAMapboxToken() {
        val api = Fake(mutableListOf(State(200, "r1", true, false)), mutableListOf())
        assertThrows(CommaNavigation.Refused::class.java) { CommaNavigation.start(api, place) }
        assertTrue(api.sent.isEmpty())
    }

    @Test fun conflictRereadsInsteadOfSelectingTwice() {
        val landed = Fake(mutableListOf(State(200, "r1", true, true), State(200, "r2", true, true, 36.1126, -115.1767)), mutableListOf(State(409)))
        CommaNavigation.start(landed, place)
        assertEquals(1, landed.sent.size)
        val moved = Fake(mutableListOf(State(200, "r1", true, true), State(200, "r2", true, true)),
            mutableListOf(State(409), State(200, "r3", true, true, 36.1126, -115.1767)))
        CommaNavigation.start(moved, place)
        assertTrue(moved.sent[1].contains("\"revision\":\"r2\""))
    }

    @Test fun reportsSignInAndUnconfirmedRoutes() {
        assertThrows(CommaNavigation.SignIn::class.java) { CommaNavigation.start(Fake(mutableListOf(State(401)), mutableListOf()), place) }
        val denied = assertThrows(java.io.IOException::class.java) { CommaNavigation.start(Fake(mutableListOf(State(403, error = "Remote Galaxy request denied")), mutableListOf()), place) }
        assertEquals("Remote Galaxy request denied", denied.message)
        val other = Fake(mutableListOf(State(200, "r1", true, true)), mutableListOf(State(200, "r2", true, true, 1.0, 2.0)))
        assertThrows(java.io.IOException::class.java) { CommaNavigation.start(other, place) }
    }

    @Test fun quotesControlCharacters() {
        assertEquals("\"a\\u000ab\\u0001\\\\\"", CommaNavigation.quote("a\nb\u0001\\"))
    }
}
