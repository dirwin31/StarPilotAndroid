package com.starpilot.galaxy

import android.content.Context
import android.net.Uri
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
class DiscordReportStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    // Exercise real authenticated encryption without depending on a physical Android Keystore.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = DiscordReportStore(context, { plain ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
    }, { encoded ->
        val bytes = Base64.getDecoder().decode(encoded)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    })
    @Before fun clear() { context.getSharedPreferences("discord-reports", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun callback(state: String, issuer: String = "https://discord.com", host: String = "callback") =
        Uri.Builder().scheme("starpilot-discord").authority(host).appendQueryParameter("state", state)
            .appendQueryParameter("iss", issuer).appendQueryParameter("code", "authorization-code").build()

    @Test fun callbackSurvivesRecreationAndCanOnlyBeConsumedOnce() {
        val attempt = store().beginLogin(1000)
        val result = store().consumeCallback(callback(attempt.state), 2000)!!
        assertEquals("authorization-code", result.first)
        assertEquals(attempt.verifier, result.second)
        assertNull(store().consumeCallback(callback(attempt.state), 2001))
    }

    @Test fun forgedCallbacksNeverConsumeTheRealAttempt() {
        val attempt = store().beginLogin(1000)
        assertNull(store().consumeCallback(callback("forged"), 2000))
        assertNull(store().consumeCallback(callback(attempt.state, "https://evil.example"), 2000))
        assertNull(store().consumeCallback(callback(attempt.state, host = "evil"), 2000))
        assertNull(store().consumeCallback(callback(attempt.state).buildUpon().appendQueryParameter("state", attempt.state).build(), 2000))
        assertNull(store().consumeCallback(callback(attempt.state).buildUpon().fragment("secret").build(), 2000))
        assertNotNull(store().consumeCallback(callback(attempt.state), 2000))
    }

    @Test fun cancelledOrExpiredAttemptsCannotBeExchanged() {
        val attempt = store().beginLogin(1000)
        val denied = callback(attempt.state).buildUpon().appendQueryParameter("error", "access_denied").build()
        assertNull(store().consumeCallback(denied, 2000))
        assertNull(store().consumeCallback(callback(attempt.state), 2001))
        val expired = store().beginLogin(1000)
        assertNull(store().consumeCallback(callback(expired.state), 601001))
        assertNull(store().consumeCallback(callback(expired.state), 2000))
    }

    @Test fun contentsStayEncryptedAndRetryPayloadSurvivesRecreationUnchanged() {
        val report = DiscordReportClient.Report("12345678-1234-1234-1234-123456789abc", "Original description", "private diagnostics", "123")
        val pending = DiscordReportStore.Pending(report, "42", 1000)
        store().credentials(DiscordReportClient.Credentials("access-secret", "refresh-secret", 9000, "42"))
        store().pending(pending)
        store().beginLogin(1000)
        val raw = context.getSharedPreferences("discord-reports", Context.MODE_PRIVATE).all.toString()
        listOf("access-secret", "refresh-secret", "private diagnostics", "Original description").forEach { assertFalse(raw.contains(it)) }
        val copy = store().pending()!!
        assertEquals(report.toJson(), copy.report.toJson())
        assertEquals("42", copy.userId)
        assertTrue(copy.retryAllowed(2000))
        assertFalse(copy.retryAllowed(1000 + 6 * 86_400_000L + 1))
        store().credentials(null)
        assertEquals(report.toJson(), store().pending()!!.report.toJson())
    }

    @Test fun uncertainDeliveryAndRateLimitsSurviveRestartAndOldRequestsCannotReplaceNewReports() {
        val report = DiscordReportClient.Report("r1", "description", "logs")
        store().pending(DiscordReportStore.Pending(report, "42", 1000))
        store().updatePending("r1", "unconfirmed")
        assertFalse(store().pending()!!.retryAllowed(2000))
        store().updatePending("r1", "retryable")
        assertTrue(store().pending()!!.retryAllowed(2000))
        store().updatePending("r1", "rate_limited", retryAt = 5000)
        assertFalse(store().pending()!!.retryAllowed(4999))
        assertTrue(store().pending()!!.retryAllowed(5000))
        store().pending(DiscordReportStore.Pending(DiscordReportClient.Report("r2", "new", "new"), "42", 2000))
        store().updatePending("r1", "sent", "https://discord.com/channels/1/2/3")
        assertEquals("r2", store().pending()!!.report.reference)
        assertEquals("ready", store().pending()!!.state)
    }

    @Test fun submissionIsClaimedOnceAndRequiresTheOriginalAccount() {
        val report = DiscordReportClient.Report("r1", "original", "logs")
        store().pending(DiscordReportStore.Pending(report, "42", 1000))
        assertNull(store().beginSubmission("r1", "another-account", 2000))
        assertNotNull(store().beginSubmission("r1", "42", 2000))
        assertNull(store().beginSubmission("r1", "42", 2001))
        assertFalse(store().pending()!!.retryAllowed(2001))
    }
}
