package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class TunnelCredentialTest {
    private val key = "AbCd0123456789xy:" + "a".repeat(64)
    @Test fun acceptsAllImportForms() {
        val credential = TunnelCredential.parse(key)!!
        assertEquals(credential, TunnelCredential.parse("  $key "))
        assertEquals(credential, TunnelCredential.parse(credential.sessionToken))
        assertEquals(credential, TunnelCredential.parse("starpilot-remote://add?key=" + java.net.URLEncoder.encode(credential.sessionToken, "UTF-8")))
    }
    @Test fun rejectsInvalidSecretsAndAuthority() {
        listOf("bad:" + "a".repeat(64), key.uppercase(), key + ":extra", "starpilot-remote://evil?key=$key", "starpilot-remote://add?key=$key&key=$key", "%zz").forEach { assertNull(it, TunnelCredential.parse(it)) }
        assertNull(TunnelCredential.galaxyId("https://galaxy.firestar.link.evil/AbCd0123456789xy"))
        assertEquals("AbCd0123456789xy", TunnelCredential.galaxyId("https://galaxy.firestar.link/AbCd0123456789xy/"))
    }
}
