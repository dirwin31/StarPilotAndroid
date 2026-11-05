package com.starpilot.galaxy

import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Client for the shared Discord report relay. The relay holds the Discord secret and webhooks. */
class DiscordReportClient(private val http: Http = UrlConnectionHttp, val base: String = BASE) {
    class Response(val status: Int, val body: String)
    fun interface Http { fun send(method: String, url: String, headers: Map<String, String>, body: String?): Response }

    class Credentials(val access: String, val refresh: String, val expiresAtMillis: Long, val userId: String) {
        fun toJson(): String = JSONObject().put("a", access).put("r", refresh).put("e", expiresAtMillis).put("u", userId).toString()
        companion object {
            fun fromJson(json: String): Credentials? = runCatching {
                JSONObject(json).let { Credentials(it.getString("a"), it.getString("r"), it.getLong("e"), it.getString("u")) }
            }.getOrNull()
        }
    }

    /** A report is captured once. Retries send these exact contents, or the relay rejects them with 409. */
    class Report(val reference: String, val description: String, val logs: String, val threadId: String? = null) {
        fun toJson(): String = JSONObject().put("reference", reference).put("description", description).put("logs", logs)
            .put("platform", "android").apply { if (threadId != null) put("threadID", threadId) }.toString()
    }

    sealed class SendResult {
        class Sent(val threadUrl: String) : SendResult()
        /** Discord declined it. Safe to retry the same saved report. */
        class Retryable(val message: String) : SendResult()
        /** Outcome unknown. Never retried automatically, or the report could be posted twice. */
        class Unconfirmed(val message: String) : SendResult()
        /** Sign-in is missing or expired. */
        class SignIn(val message: String) : SendResult()
        class Failed(val message: String, val retryAfterMillis: Long? = null) : SendResult()
    }

    fun startUrl(state: String, challenge: String): String =
        "$base/auth/discord/start?state=${enc(state)}&code_challenge=${enc(challenge)}"

    fun exchange(code: String, verifier: String): Credentials? =
        post("/auth/discord/exchange", JSONObject().put("code", code).put("verifier", verifier))?.let(::credentials)

    fun refresh(old: Credentials): Credentials? =
        post("/auth/discord/refresh", JSONObject().put("refresh_token", old.refresh))?.let(::credentials)

    fun revoke(refreshToken: String): Boolean =
        runCatching { send("POST", "/auth/discord/revoke", JSONObject().put("token", refreshToken).toString()).status in 200..299 }.getOrDefault(false)

    data class ThreadEntry(val id: String, val title: String)

    /** A failed request is distinct from a successful empty list. */
    fun reports(access: String): List<ThreadEntry>? = runCatching {
        val response = send("POST", "/v1/reports", JSONObject().put("platform", "android").toString(), "Bearer $access")
        if (response.status !in 200..299) return null
        val array: JSONArray = JSONObject(response.body).getJSONArray("reports")
        (0 until minOf(array.length(), 100)).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            if (!id.matches(Regex("[0-9]{1,20}")) || item.optString("platform") != "android") null
            else ThreadEntry(id, item.getString("title").take(150))
        }
    }.getOrNull()

    fun submit(access: String, report: Report): SendResult {
        val response = try {
            send("POST", "/v1/logs", report.toJson(), "Bearer $access")
        } catch (_: Exception) {
            return SendResult.Unconfirmed("Couldn't confirm delivery. Check Discord before sending again.")
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        return when {
            response.status in 200..299 && json?.optBoolean("sent") == true && validThreadUrl(json.optString("threadURL")) ->
                SendResult.Sent(json.getString("threadURL"))
            response.status in 200..299 -> SendResult.Unconfirmed("Couldn't confirm delivery. Check Discord before sending again.")
            response.status == 401 -> SendResult.SignIn(error.ifEmpty { "Connect with Discord first." })
            response.status == 409 -> SendResult.Unconfirmed(error.ifEmpty { "Delivery is unconfirmed." })
            // The relay permits five reports per rolling hour and supplies no Retry-After header.
            response.status == 429 -> SendResult.Failed(error.ifEmpty { "Please wait before sending another report." }, 3_600_000L)
            response.status == 503 && error.startsWith("Discord declined") -> SendResult.Retryable(error)
            response.status >= 500 -> SendResult.Unconfirmed(error.ifEmpty { "Couldn't confirm delivery. Check Discord before sending again." })
            else -> SendResult.Failed(error.ifEmpty { "The report was not accepted." })
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject? = try {
        send("POST", path, body.toString()).takeIf { it.status in 200..299 }?.let { JSONObject(it.body) }
    } catch (_: Exception) { null }

    private fun credentials(json: JSONObject): Credentials? = runCatching {
        Credentials(json.getString("access_token"), json.getString("refresh_token"),
            System.currentTimeMillis() + json.getLong("expires_in") * 1000, json.getString("user_id"))
            .also { require(it.access.isNotBlank() && it.refresh.isNotBlank() && it.userId.matches(Regex("[0-9]{1,20}")) && it.expiresAtMillis > System.currentTimeMillis()) }
    }.getOrNull()

    private fun send(method: String, path: String, body: String, authorization: String? = null) =
        http.send(method, base + path, buildMap {
            put("Content-Type", "application/json"); put("Accept", "application/json")
            if (authorization != null) put("Authorization", authorization)
        }, body)

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    object UrlConnectionHttp : Http {
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Response {
            return sendOn(URL(url).openConnection() as HttpURLConnection, method, headers, body)
        }

        fun sendOn(connection: HttpURLConnection, method: String, headers: Map<String, String>, body: String?): Response {
            try {
                connection.requestMethod = method; connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                return Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(text.length + count <= 256 * 1024) { "Relay response too large" }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty())
            } finally { connection.disconnect() }
        }
    }

    companion object {
        val BASE: String by lazy {
            val encoded = intArrayOf(
                5, 254, 211, 180, 146, 196, 52, 23, 38, 6, 238, 222, 185, 143, 111, 79, 73, 119,
                27, 251, 214, 227, 153, 109, 73, 35, 38, 82, 247, 215, 167, 152, 108, 68, 106, 3,
                172, 246, 206, 186, 151, 115, 93, 40, 71, 241, 204, 178, 182, 159, 101, 71, 127, 10, 238, 222
            )
            ByteArray(encoded.size) { index ->
                (encoded[index] xor ((0x6d + index * 29) and 0xff)).toByte()
            }.toString(Charsets.US_ASCII)
        }
        const val CALLBACK_SCHEME = "starpilot-discord"
        fun validThreadUrl(value: String): Boolean = Regex("https://discord\\.com/channels/[0-9]+/[0-9]+(?:/[0-9]+)?").matches(value)
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        /** 43 URL-safe characters, as the relay requires for state, verifier and challenge. */
        fun nonce(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)
        fun challenge(verifier: String): String = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
