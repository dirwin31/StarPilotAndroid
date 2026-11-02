package com.starpilot.galaxy

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Imports only from an authenticated, direct-local Galaxy session. Internet is not required. */
object TunnelImport {
    private val busy = AtomicBoolean()
    internal data class Reply(val status: Int, val body: JSONObject, val cookie: String = "")

    internal fun fetch(cookie: String, progress: (Int) -> Unit = {}, request: (String, String) -> Reply): TunnelCredential? {
        progress(1)
        val session = request("/api/auth/session", cookie)
        if (session.status != 200 || !session.body.optBoolean("authenticated") || !session.body.optBoolean("localAccess")) return null
        progress(2)
        val result = request("/api/galaxy/session", session.cookie.ifBlank { cookie })
        return if (result.status == 200) credential(result.body) else null
    }

    internal fun credential(body: JSONObject): TunnelCredential? {
        if (!body.optBoolean("paired")) return null
        val credential = TunnelCredential.parse(body.optString("sessionToken")) ?: return null
        if (body.has("slug") && body.optString("slug") != credential.slug) return null
        return credential
    }

    fun importIfNeeded(context: Context, network: Network, origin: String, cookie: String, current: () -> Boolean) {
        val store = TunnelStore(context.applicationContext)
        if (store.load() != null || Networks.localOrigin(origin) != origin || !busy.compareAndSet(false, true)) return
        Thread {
            val credential = runCatching {
                read(network, origin, cookie)
            }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                try {
                    // A navigation, forget action or manual pairing supersedes this request.
                    if (credential != null && current() && store.load() == null) {
                        runCatching { store.save(credential, verified = false) }.onSuccess {
                            Toast.makeText(context, "Remote tunnel access automatically added and saved.", Toast.LENGTH_LONG).show()
                        }
                    }
                } finally { busy.set(false) }
            }
        }.start()
    }

    internal fun read(network: Network, origin: String, cookie: String = "", progress: (Int) -> Unit = {}): TunnelCredential? {
        require(Networks.localOrigin(origin) == origin)
        return fetch(cookie, progress) { path, session ->
            val connection = network.openConnection(URL(origin + path)) as HttpURLConnection
            try {
                connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (session.isNotBlank()) connection.setRequestProperty("Cookie", session)
                val status = connection.responseCode
                if (status != 200) Reply(status, JSONObject()) else {
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 8192)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    Reply(status, JSONObject(String(bytes, Charsets.UTF_8)), connection.getHeaderField("Set-Cookie")?.substringBefore(';').orEmpty())
                }
            } finally { connection.disconnect() }
        }
    }

    fun accept(context: Context, body: JSONObject, current: () -> Boolean) {
        val credential = credential(body) ?: return
        if (TunnelProbe.check(context, credential) == TunnelProbe.Result.REACHABLE) Handler(Looper.getMainLooper()).post {
            if (current() && TunnelStore(context).load() == null) runCatching { TunnelStore(context).save(credential) }
        }
    }
}
