package com.starpilot.galaxy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DiscordReportClientTest {
    private class Fake(var status: Int = 200, var body: String = "{}", var fail: Boolean = false) : DiscordReportClient.Http {
        var method = ""; var url = ""; var headers = emptyMap<String, String>(); var sent: String? = null
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): DiscordReportClient.Response {
            this.method = method; this.url = url; this.headers = headers; sent = body
            if (fail) throw java.io.IOException("offline")
            return DiscordReportClient.Response(status, body_)
        }
        private val body_ get() = body
    }
    private val report = DiscordReportClient.Report("0b8f6c3e-1a2b-4c3d-8e9f-001122334455", "No connection", "log")

    @Test fun noncesMatchTheRelayRule() {
        val value = DiscordReportClient.nonce()
        assertTrue(Regex("[A-Za-z0-9_-]{43,128}").matches(value))
        assertTrue(Regex("[A-Za-z0-9_-]{43,128}").matches(DiscordReportClient.challenge(value)))
        assertNotEquals(value, DiscordReportClient.nonce())
    }

    @Test fun challengeMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DiscordReportClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun startUrlCarriesStateAndChallenge() {
        assertEquals("https://x/auth/discord/start?state=s&code_challenge=c", DiscordReportClient(Fake(), "https://x").startUrl("s", "c"))
    }

    @Test fun encodedOriginStillTargetsTheExpectedRelayForLoginAndReports() {
        val expected = "https://starpilot-log-relay.nathan-g-hubbard.workers.dev"
        assertEquals(expected, DiscordReportClient.BASE)
        val http = Fake()
        val client = DiscordReportClient(http)
        assertEquals("$expected/auth/discord/start?state=state&code_challenge=challenge", client.startUrl("state", "challenge"))
        client.submit("token", report)
        assertEquals("$expected/v1/logs", http.url)
    }

    @Test fun successfulSendReturnsThreadAndSendsAndroidPlatform() {
        val http = Fake(200, """{"sent":true,"reference":"r","threadURL":"https://discord.com/channels/1/2/3"}""")
        val result = DiscordReportClient(http).submit("tok", report)
        assertEquals("https://discord.com/channels/1/2/3", (result as DiscordReportClient.SendResult.Sent).threadUrl)
        assertEquals("Bearer tok", http.headers["Authorization"])
        assertEquals("application/json", http.headers["Content-Type"])
        assertTrue(http.url.endsWith("/v1/logs"))
        val json = JSONObject(http.sent!!)
        assertEquals("android", json.getString("platform")); assertEquals("log", json.getString("logs"))
        assertFalse(json.has("threadID"))
    }

    @Test fun threadFollowUpsIncludeTheThread() {
        val http = Fake(200, """{"sent":true,"threadURL":"https://discord.com/channels/1/2/3"}""")
        DiscordReportClient(http).submit("tok", DiscordReportClient.Report("r", "d", "l", "123"))
        assertEquals("123", JSONObject(http.sent!!).getString("threadID"))
    }

    @Test fun unconfirmedOutcomesAreNeverRetryable() {
        val conflict = Fake(409, """{"error":"Delivery is unconfirmed. Diagnostics may have arrived privately."}""")
        assertTrue(DiscordReportClient(conflict).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
        assertTrue(DiscordReportClient(Fake(fail = true)).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
        assertTrue(DiscordReportClient(Fake(500, "oops")).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
        assertTrue(DiscordReportClient(Fake(200, "{}")).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
        assertTrue(DiscordReportClient(Fake(200, """{"sent":true,"threadURL":"https://evil.example/x"}""")).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
    }

    @Test fun onlyDiscordDeclinedIsRetryable() {
        val declined = Fake(503, """{"error":"Discord declined this delivery. Retry the saved report; confirmed private diagnostics will not be uploaded again."}""")
        assertTrue(DiscordReportClient(declined).submit("t", report) is DiscordReportClient.SendResult.Retryable)
        assertTrue(DiscordReportClient(Fake(503, """{"error":"Report destination is unavailable"}""")).submit("t", report) is DiscordReportClient.SendResult.Unconfirmed)
    }

    @Test fun signInAndRateLimitErrors() {
        assertTrue(DiscordReportClient(Fake(401, """{"error":"Please reconnect Discord"}""")).submit("t", report) is DiscordReportClient.SendResult.SignIn)
        assertTrue(DiscordReportClient(Fake(429, """{"error":"Please wait before sending another report."}""")).submit("t", report) is DiscordReportClient.SendResult.Failed)
        assertTrue(DiscordReportClient(Fake(400, """{"error":"Add a short description and diagnostics"}""")).submit("t", report) is DiscordReportClient.SendResult.Failed)
    }

    @Test fun exchangeAndRefreshParseCredentials() {
        val http = Fake(200, """{"access_token":"a","refresh_token":"r","expires_in":604800,"user_id":"42"}""")
        val credentials = DiscordReportClient(http).exchange("code", "verifier")!!
        assertEquals("a", credentials.access); assertEquals("r", credentials.refresh); assertEquals("42", credentials.userId)
        assertTrue(credentials.expiresAtMillis > System.currentTimeMillis())
        assertEquals("code", JSONObject(http.sent!!).getString("code"))
        assertEquals("r", JSONObject(DiscordReportClient(http).refresh(credentials).let { http.sent!! }).getString("refresh_token"))
        assertNull(DiscordReportClient(Fake(401, """{"error":"x"}""")).exchange("c", "v"))
        assertNull(DiscordReportClient(Fake(fail = true)).refresh(credentials))
    }

    @Test fun credentialsRoundTrip() {
        val original = DiscordReportClient.Credentials("a", "r", 123L, "42")
        val copy = DiscordReportClient.Credentials.fromJson(original.toJson())!!
        assertEquals("a", copy.access); assertEquals("r", copy.refresh); assertEquals(123L, copy.expiresAtMillis); assertEquals("42", copy.userId)
        assertNull(DiscordReportClient.Credentials.fromJson("not json"))
    }

    @Test fun reportListIsAccountAuthenticatedAndAndroidOnly() {
        val http = Fake(200, """{"reports":[{"id":"123","title":"Android report","platform":"android"},{"id":"456","title":"iOS report","platform":"ios"}]}""")
        val threads = DiscordReportClient(http).reports("access")!!
        assertEquals(listOf("123"), threads.map { it.id })
        assertEquals("Bearer access", http.headers["Authorization"])
        assertEquals("android", JSONObject(http.sent!!).getString("platform"))
        assertTrue(http.url.endsWith("/v1/reports"))
        assertNull(DiscordReportClient(Fake(401)).reports("expired"))
        assertNull(DiscordReportClient(Fake(200, "not json")).reports("access"))
    }

    @Test fun diagnosticsRedactCredentialsAndAddressesWithoutLosingVersion() {
        DiagnosticLog.clear()
        DiagnosticLog.record("error Bearer abc.secret token=secret-value https://private.firestar.link/session 192.168.1.2 AA:BB:CC:DD:EE:FF")
        val snapshot = DiagnosticLog.snapshot(mapOf("App version" to "7.0.0.6 (42)"))
        listOf("abc.secret", "secret-value", "private.firestar.link", "192.168.1.2", "AA:BB:CC:DD:EE:FF").forEach { assertFalse(snapshot.contains(it)) }
        assertTrue(snapshot.contains("7.0.0.6 (42)"))
        DiagnosticLog.clear()
    }

    @Test fun logIsBoundedDeduplicatedAndSingleLine() {
        DiagnosticLog.clear()
        repeat(DiagnosticLog.MAX_EVENTS + 50) { DiagnosticLog.record("event $it") }
        DiagnosticLog.record("event ${DiagnosticLog.MAX_EVENTS + 49}")
        DiagnosticLog.record("multi\nline\r" + "x".repeat(900))
        val snapshot = DiagnosticLog.snapshot(mapOf("App" to "7.0.0.6\ninjected"), Instant.parse("2026-11-03T00:00:00Z"))
        val events = snapshot.substringAfter("Recent events:\n").lines()
        assertEquals(DiagnosticLog.MAX_EVENTS, events.size)
        assertFalse(snapshot.contains("event 0\n")); assertEquals(1, events.count { it.endsWith("event ${DiagnosticLog.MAX_EVENTS + 49}") })
        assertTrue(events.last().length <= 40 + DiagnosticLog.MAX_EVENT_CHARS)
        assertTrue(snapshot.contains("App: 7.0.0.6 injected"))
        DiagnosticLog.clear()
    }
}
