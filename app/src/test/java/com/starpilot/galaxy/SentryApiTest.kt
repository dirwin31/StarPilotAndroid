package com.starpilot.galaxy

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SentryApiTest {
    @Test fun nativeRequestsFollowSessionEstablishedAndRotatedAfterPageLoad() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<List<String>> {
                    (1..2).map {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val body = "{}"
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n" + body).toByteArray())
                            headers.single { it.startsWith("Cookie:", ignoreCase = true) }.substringAfter(':').trim()
                        }
                    }
                }
                var cookie = ""
                val api = SentryApi("http://127.0.0.1:${server.localPort}", "stale", cookieProvider = { cookie })
                cookie = "galaxy_session=established"
                api.request("/api/software/status?history=0")
                cookie = "galaxy_session=rotated"
                api.request("/api/software/status?history=0")
                assertEquals(listOf("galaxy_session=established", "galaxy_session=rotated"), received.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
