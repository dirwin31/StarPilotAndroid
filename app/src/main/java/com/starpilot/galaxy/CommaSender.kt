package com.starpilot.galaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Sends a shared place to comma over whatever reaches it: open Bluetooth, comma's Wi-Fi or LAN, the Remote route, then a new Bluetooth link. */
class CommaSender(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    /** Returns null once comma is routing to the place, else why it could not be sent. */
    fun send(place: SharedDestination.Place): String? {
        var reason: String? = null
        val routes = listOf<() -> CommaNavigation.Api?>({ BleTransport.active?.takeIf { it.open }?.let(::BluetoothApi) }, ::lan, ::tunnel, ::bluetooth)
        for (route in routes) {
            val api = try { route() } catch (_: Exception) { null } ?: continue
            try {
                CommaNavigation.start(api, place)
                return null
            } catch (error: CommaNavigation.Refused) {
                return error.message
            } catch (error: Exception) {
                if (api is HttpApi && api.tunnel && error is CommaNavigation.SignIn) reason = "Pair the Remote route again"
            } finally { (api as? BluetoothApi)?.takeIf { it.own }?.transport?.close() }
        }
        return reason ?: "StarPilot is not reachable"
    }

    private fun lan(): CommaNavigation.Api? = Networks.commaLan(context)?.let { (network, origin) -> HttpApi(network, origin, origin, false) }

    private fun tunnel(): CommaNavigation.Api? {
        val credential = TunnelStore(context).load() ?: return null
        val cm = Networks.manager(context)
        // The comma hotspot has no internet; the tunnel needs a network that does.
        val network = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).firstOrNull {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: return null
        return HttpApi(network, credential.webUrl.trimEnd('/'), TUNNEL_ORIGIN, true, "galaxy_session=${credential.sessionToken}")
    }

    private fun bluetooth(): CommaNavigation.Api? {
        if (BleTransport.active != null || BleTransport.busy) return null
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val pairing = PairingStore(context)
        if (pairing.stale()) return null
        if (context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled != true) return null
        val key = pairing.load() ?: return null
        val address = pairing.deviceAddress() ?: return null
        val transport = BleTransport(context, key)
        return try { transport.connect(address); BluetoothApi(transport, own = true) } catch (_: Exception) { transport.close(); null }
    }

    private class BluetoothApi(val transport: BleTransport, val own: Boolean = false) : CommaNavigation.Api {
        override fun status() = call("GET", CommaNavigation.STATUS, "")
        override fun action(body: String) = call("POST", CommaNavigation.ACTION, body)
        private fun call(method: String, path: String, body: String): CommaNavigation.State {
            val reply = transport.request(JSONObject().put("method", method).put("path", path)
                .put("headers", JSONObject().put("content-type", "application/json"))
                .put("body", Base64.getEncoder().encodeToString(body.toByteArray())), 30)
            return state(reply.getInt("status"), String(Base64.getDecoder().decode(reply.optString("body"))))
        }
    }

    private class HttpApi(val network: Network, val base: String, val origin: String, val tunnel: Boolean, var cookie: String = "") : CommaNavigation.Api {
        override fun status() = call("GET", CommaNavigation.STATUS, null)
        override fun action(body: String) = call("POST", CommaNavigation.ACTION, body)
        private fun call(method: String, path: String, body: String?): CommaNavigation.State {
            for (attempt in 0..1) {
                if (cookie.isEmpty() && !tunnel) cookie = open("/api/auth/session").run {
                    try { getHeaderField("Set-Cookie")?.substringBefore(';') ?: "" } finally { disconnect() }
                }
                val connection = open(path)
                try {
                    connection.requestMethod = method
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = connection.responseCode
                    if (code == 401 && !tunnel && attempt == 0) { cookie = ""; continue }
                    val stream = if (code < 400) connection.inputStream else connection.errorStream
                    val data = java.io.ByteArrayOutputStream()
                    stream?.use { input ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer).takeIf { it >= 0 } ?: break
                            data.write(buffer, 0, count)
                            if (data.size() > MAX_BODY) throw IOException("Galaxy reply too large")
                        }
                    }
                    return state(code, data.toString("UTF-8"))
                } finally { connection.disconnect() }
            }
            throw CommaNavigation.SignIn()
        }
        private fun open(path: String) = (network.openConnection(URL(base + path)) as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 8000; instanceFollowRedirects = false
            setRequestProperty("Origin", origin)
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }
    }

    companion object {
        private const val MAX_BODY = 1024 * 1024
        private const val TUNNEL_ORIGIN = "https://galaxy.firestar.link"
        private fun state(code: Int, text: String): CommaNavigation.State {
            val json = try { JSONObject(text) } catch (_: Exception) { return CommaNavigation.State(code) }
            val destination = json.optJSONObject("destination")
            return CommaNavigation.State(code, json.optString("revision"), json.optBoolean("enabled"), json.optBoolean("hasKey"),
                destination?.optDouble("latitude")?.takeUnless { it.isNaN() }, destination?.optDouble("longitude")?.takeUnless { it.isNaN() },
                json.optString("error").ifEmpty { null })
        }
    }
}
