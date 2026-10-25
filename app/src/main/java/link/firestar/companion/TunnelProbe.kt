package link.firestar.companion

import android.content.Context
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object TunnelProbe {
    enum class Result { REACHABLE, BAD_KEY, UNREACHABLE }
    fun check(context: Context, credential: TunnelCredential): Result {
        val cm = Networks.manager(context)
        val network = cm.activeNetwork ?: return Result.UNREACHABLE
        if (cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) return Result.UNREACHABLE
        return runCatching {
            val connection = network.openConnection(URL(credential.webUrl + "api/auth/session")) as HttpURLConnection
            try {
                connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Cookie", "galaxy_session=${credential.sessionToken}")
                when (connection.responseCode) {
                    401, 403 -> Result.BAD_KEY
                    200 -> if (JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optBoolean("authenticated")) Result.REACHABLE else Result.BAD_KEY
                    else -> Result.UNREACHABLE
                }
            } finally { connection.disconnect() }
        }.getOrDefault(Result.UNREACHABLE)
    }
}
