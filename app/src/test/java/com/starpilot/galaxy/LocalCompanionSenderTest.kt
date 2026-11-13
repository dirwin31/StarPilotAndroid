package com.starpilot.galaxy

import android.net.Network
import android.os.SystemClock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [LocalCompanionSenderTest.TestNetwork::class])
class LocalCompanionSenderTest {
    @Implements(Network::class)
    class TestNetwork {
        companion object { lateinit var open: (URL) -> URLConnection }
        @Implementation fun openConnection(url: URL): URLConnection = open(url)
    }
    private class Connection(url: URL, val reply: String = "{}", val code: Int = 200, val ready: () -> Unit = {}) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() { ready(); connected = true }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Set-Cookie") "galaxy_session=test" else null
        override fun getInputStream() = ByteArrayInputStream(reply.toByteArray())
        override fun getOutputStream() = body
    }
    @Test fun samplesNewestFixAfterTcpConnectAndCachesClockProbe() {
        val stream = PhoneGpsStream("session")
        val fix = PhoneGps.Fix(40.0, -110.0, 5f, 1)
        assertTrue(stream.offer(fix, SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos()))
        val posted = mutableListOf<Connection>()
        var probes = 0
        var locationCalls = 0
        TestNetwork.open = { url ->
            when {
                url.path != PhoneGps.PATH -> Connection(url)
                locationCalls++ == 0 -> { probes = 1; Connection(url, """{"ok":true,"serverMonoTimeNs":100000000000}""") }
                else -> Connection(url, """{"accepted":true}""", ready = {
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                    stream.offer(fix.copy(latitude = 41.0), SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos())
                }).also { posted += it }
            }
        }
        val sender = LocalCompanionSender(RuntimeEnvironment.getApplication())
        val network = Shadow.newInstanceOf(Network::class.java)
        repeat(2) {
            sender.postLocal<Long>(network, "http://comma", PhoneGps.PATH, null, true) { server ->
                val (sequence, payload) = stream.payload(SystemClock.elapsedRealtimeNanos(), serverMonoTimeNs = server)!!
                sequence to JSONObject(payload).toString().toByteArray()
            }
        }
        assertEquals(1, probes)
        assertEquals(3, locationCalls)
        val payload = JSONObject(posted[0].body.toString("UTF-8"))
        assertEquals(2L, payload.getLong("sequence"))
        assertEquals(41.0, payload.getDouble("latitude"), 0.0)
        assertEquals(0L, payload.getLong("ageMs"))
        assertTrue(payload.getLong("serverMonoTimeNs") >= 103000000000)
    }
    @Test fun expiredFixIsRejectedAfterSlowTcpConnectBeforeWritingBody() {
        val stream = PhoneGpsStream("session")
        val now = SystemClock.elapsedRealtimeNanos()
        assertTrue(stream.offer(PhoneGps.Fix(40.0, -110.0, 5f, 1), now, now))
        var post: Connection? = null
        TestNetwork.open = { url -> Connection(url, ready = {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        }).also { if (url.path == PhoneGps.PATH) post = it } }
        val sender = LocalCompanionSender(RuntimeEnvironment.getApplication())
        assertThrows(IllegalStateException::class.java) {
            sender.postLocal<Long>(Shadow.newInstanceOf(Network::class.java), "http://comma", PhoneGps.PATH, null, false) {
                val (sequence, payload) = stream.payload(SystemClock.elapsedRealtimeNanos()) ?: error("Expired")
                sequence to JSONObject(payload).toString().toByteArray()
            }
        }
        assertEquals(0, post!!.body.size())
    }

    @Test fun legacyEndpointMissIsCachedAndDoesNotBlockPosts() {
        var calls = 0
        TestNetwork.open = { url ->
            if (url.path != PhoneGps.PATH) Connection(url)
            else Connection(url, code = if (calls++ == 0) 404 else 200)
        }
        val sender = LocalCompanionSender(RuntimeEnvironment.getApplication())
        val network = Shadow.newInstanceOf(Network::class.java)
        repeat(2) {
            sender.postLocal<Long>(network, "http://comma", PhoneGps.PATH, null, true) { server ->
                assertNull(server)
                1L to "{}".toByteArray()
            }
        }
        assertEquals(3, calls)
    }
}
