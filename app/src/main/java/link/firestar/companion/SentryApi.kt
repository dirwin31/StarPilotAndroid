package link.firestar.companion

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SentryApi(val origin: String, private val cookie: String, private val network: Network? = null,
                private val bridge: ((String, JSONObject?) -> JSONObject)? = null) {
    fun request(path: String, body: JSONObject? = null): JSONObject = bridge?.invoke(path, body) ?: JSONObject(String(bytes(path, body)))
    fun bytes(path: String, body: JSONObject? = null): ByteArray {
        require(path.startsWith("/api/") && !path.contains(".."))
        val connection = (network?.openConnection(URL(origin + path)) ?: URL(origin + path).openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = 2000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cookie", cookie)
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Origin", if (origin.startsWith("https://galaxy.firestar.link/")) "https://galaxy.firestar.link" else origin)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            require(connection.responseCode == 200) { "Galaxy HTTP ${connection.responseCode}" }
            val value = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(value.size <= 1024 * 1024)
            return value
        } finally { connection.disconnect() }
    }
    companion object {
        fun best(context: Context): SentryApi? {
            Networks.commaLan(context)?.let { (network, origin) ->
                val local = runCatching {
                    val connection = network.openConnection(URL("$origin/api/auth/session")) as HttpURLConnection
                    try {
                        connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.instanceFollowRedirects = false
                        if (connection.responseCode != 200) return@runCatching null
                        val session = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        if (!session.optBoolean("authenticated") || !session.optBoolean("localAccess")) null
                        else SentryApi(origin, connection.getHeaderField("Set-Cookie")?.substringBefore(';').orEmpty(), network)
                    } finally { connection.disconnect() }
                }.getOrNull()
                if (local != null) return local
            }
            val store = TunnelStore(context)
            val credential = store.load() ?: return null
            return when (TunnelProbe.check(context, credential)) {
                TunnelProbe.Result.REACHABLE -> SentryApi(credential.webUrl.trimEnd('/'), "galaxy_session=${credential.sessionToken}", Networks.manager(context).activeNetwork)
                TunnelProbe.Result.BAD_KEY -> { store.clear(); null }
                else -> null
            }
        }
    }
}
