package com.starpilot.galaxy

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class TunnelStoreTest {
    private val secret = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    private val context get() = RuntimeEnvironment.getApplication()
    // Substitute a JVM AES key for AndroidKeyStore; production uses the persistent device keystore.
    private fun store() = TunnelStore(context, encrypt = { bytes ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret) }
        Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
    }, decrypt = { value ->
        val bytes = Base64.getDecoder().decode(value)
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size))
    })
    private val credential = TunnelCredential("AbCd0123456789xy", "b".repeat(64))

    @Before fun clear() { context.getSharedPreferences("tunnel", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun pendingPairingSurvivesNewStoreAndIsNotMarkedVerified() {
        store().save(credential, verified = false)
        val reopened = store()
        assertEquals(credential, reopened.load())
        assertFalse(reopened.verified())
        val prefs = context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it == credential.token || it == credential.slug })
        reopened.save(credential)
        assertEquals(credential, store().load())
        assertTrue(store().verified())
    }

    @Test fun forgetPersistsAndCorruptCredentialsFailClosed() {
        store().save(credential)
        store().clear()
        assertNull(store().load())
        assertFalse(store().verified())
        store().save(credential)
        context.getSharedPreferences("tunnel", Context.MODE_PRIVATE).edit().putString("token", "corrupt").commit()
        assertNull(store().load())
        assertFalse(store().verified())
    }
}
