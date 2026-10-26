package com.starpilot.galaxy

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object TunnelImport {
    private val busy = AtomicBoolean()
    fun importIfNeeded(context: Context, origin: String, cookie: String) {
        if (TunnelStore(context).load() != null || Networks.localOrigin(origin) == null || !busy.compareAndSet(false, true)) return
        Thread {
            try {
                val network = Networks.commaLan(context)?.first ?: return@Thread
                val connection = network.openConnection(URL("$origin/api/galaxy/session")) as HttpURLConnection
                try {
                    connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cookie", cookie)
                    if (connection.responseCode == 200) accept(context, JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                } finally { connection.disconnect() }
            } catch (_: Exception) {} finally { busy.set(false) }
        }.start()
    }
    fun accept(context: Context, body: JSONObject) {
        if (!body.optBoolean("paired")) return
        val credential = TunnelCredential.parse(body.optString("sessionToken")) ?: return
        if (TunnelProbe.check(context, credential) == TunnelProbe.Result.REACHABLE) TunnelStore(context).save(credential)
    }
}
