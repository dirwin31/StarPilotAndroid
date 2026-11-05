package com.starpilot.galaxy

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest

/** All OAuth state and report contents are encrypted with the existing Android Keystore key. */
class DiscordReportStore(context: Context,
    private val encrypt: (ByteArray) -> String = SecureStore::encrypt,
    private val decrypt: (String) -> ByteArray = SecureStore::decrypt) {
    private val prefs = context.getSharedPreferences("discord-reports", Context.MODE_PRIVATE)

    private fun read(key: String): String? = prefs.getString(key, null)?.let { decrypt(it).toString(Charsets.UTF_8) }
    private fun write(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, encrypt(value.toByteArray(Charsets.UTF_8)))
        check(editor.commit()) { "Could not save Discord report data" }
    }

    @Synchronized fun credentials(): DiscordReportClient.Credentials? =
        read("credentials")?.let { requireNotNull(DiscordReportClient.Credentials.fromJson(it)) }
    @Synchronized fun credentials(value: DiscordReportClient.Credentials?) = write("credentials", value?.toJson())

    data class Attempt(val state: String, val verifier: String, val created: Long)
    @Synchronized fun beginLogin(now: Long = System.currentTimeMillis()): Attempt {
        val attempt = Attempt(DiscordReportClient.nonce(), DiscordReportClient.nonce(), now)
        write("attempt", JSONObject().put("state", attempt.state).put("verifier", attempt.verifier).put("created", now).toString())
        return attempt
    }
    @Synchronized fun cancelLogin() = write("attempt", null)

    /** Return the verifier only for a fresh, matching, single-use callback from Discord. */
    @Synchronized fun consumeCallback(uri: Uri, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (!uri.isHierarchical || uri.scheme != DiscordReportClient.CALLBACK_SCHEME || uri.encodedAuthority != "callback" ||
            !uri.path.isNullOrEmpty() || uri.fragment != null) return null
        if (listOf("state", "iss", "code", "error").any { uri.getQueryParameters(it).size > 1 }) return null
        val saved = read("attempt")?.let(::JSONObject) ?: return null
        val state = uri.getQueryParameter("state") ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), saved.getString("state").toByteArray())) return null
        val age = now - saved.getLong("created")
        if (age !in 0..600_000L) { cancelLogin(); return null }
        if (uri.getQueryParameter("iss") != "https://discord.com") return null
        cancelLogin()
        if (uri.getQueryParameter("error") != null) return null
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: return null
        return code to saved.getString("verifier")
    }

    data class Pending(val report: DiscordReportClient.Report, val userId: String, val created: Long,
        val state: String = "ready", val threadUrl: String = "", val retryAt: Long = 0) {
        fun retryAllowed(now: Long = System.currentTimeMillis()) =
            state in setOf("ready", "retryable", "signin", "rate_limited") && now >= retryAt && now - created in 0..6 * 86_400_000L
        fun toJson(): String = JSONObject().put("report", JSONObject(report.toJson())).put("user", userId)
            .put("created", created).put("state", state).put("threadUrl", threadUrl).put("retryAt", retryAt).toString()
    }
    @Synchronized fun pending(): Pending? = read("pending")?.let {
        val json = JSONObject(it)
        val report = json.getJSONObject("report")
        Pending(DiscordReportClient.Report(report.getString("reference"), report.getString("description"),
            report.getString("logs"), if (report.has("threadID")) report.getString("threadID") else null),
            json.getString("user"), json.getLong("created"), json.getString("state"), json.optString("threadUrl"), json.optLong("retryAt"))
    }
    @Synchronized fun pending(value: Pending?) = write("pending", value?.toJson())

    /** Claim a send durably before starting it. Other activity instances see an uncertain delivery. */
    @Synchronized fun beginSubmission(reference: String, userId: String, now: Long = System.currentTimeMillis()): Pending? {
        val current = pending() ?: return null
        if (current.report.reference != reference || current.userId != userId || !current.retryAllowed(now)) return null
        pending(current.copy(state = "unconfirmed"))
        return current
    }

    /** An old activity's completed request must never replace a newer report or a discarded report. */
    @Synchronized fun updatePending(reference: String, state: String, threadUrl: String = "", retryAt: Long = 0) {
        val current = pending() ?: return
        if (current.report.reference == reference) pending(current.copy(state = state, threadUrl = threadUrl, retryAt = retryAt))
    }
}
