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

    private val link = "https://www.google.com/maps/place/Bellagio/@36.1,-115.1,17z/data=!3d36.1126!4d-115.1767"

    @Test fun shareStartsThePlaceCommaFound() {
        val api = Fake(mutableListOf(State(200, "r1")), mutableListOf(State(200, "r2", true, true, sharedName = "Bellagio Hotel")))
        assertEquals(CommaNavigation.Outcome.Started("Bellagio Hotel"), CommaNavigation.share(api, "Bellagio\n$link"))
        assertEquals("""{"action":"share","revision":"r1","text":"Bellagio\u000a$link"}""", api.sent.single())
    }

    @Test fun shareAsksForASearchOrShowsTheRefusal() {
        val ambiguous = Fake(mutableListOf(State(200, "r1")), mutableListOf(State(400, error = "Several places match", query = true)))
        assertEquals(CommaNavigation.Outcome.NeedsSearch, CommaNavigation.share(ambiguous, "Starbucks"))
        val refused = Fake(mutableListOf(State(200, "r1")), mutableListOf(State(400, error = "Set up a map provider in Galaxy navigation")))
        assertEquals(CommaNavigation.Outcome.Refused("Set up a map provider in Galaxy navigation"), CommaNavigation.share(refused, link))
        assertEquals(1, refused.sent.size)
    }

    @Test fun shareRetriesOnceAfterAConflict() {
        val api = Fake(mutableListOf(State(200, "r1"), State(200, "r2")), mutableListOf(State(409), State(200, "r3", sharedName = "Bellagio")))
        assertEquals(CommaNavigation.Outcome.Started("Bellagio"), CommaNavigation.share(api, link))
        assertTrue(api.sent[1].contains("\"revision\":\"r2\""))
        val busy = Fake(mutableListOf(State(200, "r1"), State(200, "r2"), State(200, "r3")), mutableListOf(State(409), State(409)))
        assertThrows(java.io.IOException::class.java) { CommaNavigation.share(busy, link) }
        assertEquals(2, busy.sent.size)
    }

    @Test fun olderCommaGetsThePlaceParsedOnThePhone() {
        val api = Fake(mutableListOf(State(200, "r1", true, true), State(200, "r2", true, true)),
            mutableListOf(State(400, error = CommaNavigation.OLD_COMMA), State(200, "r3", true, true, 36.1126, -115.1767)))
        assertEquals(CommaNavigation.Outcome.Started("Bellagio"), CommaNavigation.share(api, "Bellagio\n$link"))
        assertTrue(api.sent[1].startsWith("""{"action":"select","revision":"r2","destination":{"name":"Bellagio","""))
        val search = Fake(mutableListOf(State(200, "r1", true, true)), mutableListOf(State(400, error = CommaNavigation.OLD_COMMA)))
        assertEquals(CommaNavigation.Outcome.NeedsSearch, CommaNavigation.share(search, "Bellagio, Las Vegas"))
        val noKey = Fake(mutableListOf(State(200, "r1", true, false), State(200, "r2", true, false)), mutableListOf(State(400, error = CommaNavigation.OLD_COMMA)))
        assertTrue(CommaNavigation.share(noKey, link) is CommaNavigation.Outcome.Refused)
    }

    @Test fun shareReportsSignInAndUnreachable() {
        assertThrows(CommaNavigation.SignIn::class.java) { CommaNavigation.share(Fake(mutableListOf(State(401)), mutableListOf()), link) }
        assertThrows(CommaNavigation.SignIn::class.java) { CommaNavigation.share(Fake(mutableListOf(State(200, "r1")), mutableListOf(State(401))), link) }
        assertThrows(java.io.IOException::class.java) { CommaNavigation.share(Fake(mutableListOf(State(502)), mutableListOf()), link) }
    }

    @Test fun quotesControlCharacters() {
        assertEquals("\"a\\u000ab\\u0001\\\\\"", CommaNavigation.quote("a\nb\u0001\\"))
    }
}
