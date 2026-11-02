package com.starpilot.galaxy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class TunnelImportTest {
    private val key = TunnelCredential("AbCd0123456789xy", "a".repeat(64))
    private fun body() = JSONObject().put("paired", true).put("slug", key.slug).put("sessionToken", key.sessionToken)

    @Test fun opensLocalSessionThenFetchesKeyWithoutInternetVerification() {
        val steps = mutableListOf<Int>()
        val paths = mutableListOf<String>()
        val imported = TunnelImport.fetch("old", { steps.add(it) }) { path, cookie ->
            paths.add(path)
            when (path) {
                "/api/auth/session" -> {
                    assertEquals("old", cookie)
                    TunnelImport.Reply(200, JSONObject().put("authenticated", true).put("localAccess", true), "galaxy_session=fresh")
                }
                "/api/galaxy/session" -> {
                    assertEquals("galaxy_session=fresh", cookie)
                    TunnelImport.Reply(200, body())
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals(key, imported)
        assertEquals(listOf("/api/auth/session", "/api/galaxy/session"), paths)
        assertEquals(listOf(1, 2), steps)
    }

    @Test fun refusesUnauthenticatedOrNonLocalSessionsAndRedirects() {
        for ((status, authenticated, local) in listOf(Triple(401, false, true), Triple(200, true, false), Triple(200, false, true), Triple(302, true, true))) {
            var calls = 0
            assertNull(TunnelImport.fetch("") { _, _ ->
                calls++
                TunnelImport.Reply(status, JSONObject().put("authenticated", authenticated).put("localAccess", local))
            })
            assertEquals(1, calls)
        }
    }

    @Test fun rejectsMissingInvalidAndMismatchedKeys() {
        assertNull(TunnelImport.credential(JSONObject().put("paired", false)))
        assertNull(TunnelImport.credential(body().put("sessionToken", "bad")))
        assertNull(TunnelImport.credential(body().put("slug", "DifferentGalaxy1")))
        assertEquals(key, TunnelImport.credential(body()))
    }

    @Test fun serverMustAuthorizeKeyExport() {
        assertNull(TunnelImport.fetch("") { path, _ ->
            if (path == "/api/auth/session") TunnelImport.Reply(200, JSONObject().put("authenticated", true).put("localAccess", true))
            else TunnelImport.Reply(403, body())
        })
    }
}
