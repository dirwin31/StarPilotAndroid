package com.starpilot.galaxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Authenticated, local-only posts shared by GPS and navigation. Worker threads only. */
internal class LocalCompanionSender(private val context: Context) : Closeable {
    private var cookie = ""
    private var cookieOrigin = ""
    private var lastBluetoothAttempt = -15_000L
    private var own: BleTransport? = null

    fun <T> post(path: String, deadlineSeconds: Long = 90, onResponse: ((String, JSONObject) -> Unit)? = null, latestBody: () -> Pair<T, ByteArray>): T {
        val ble = BleTransport.active?.takeIf { it.open }
        if (ble == null) Networks.commaLan(context)?.let {
            try { return postLocal(it.first, it.second, path, onResponse, latestBody) }
            catch (_: Exception) { /* A disappearing Wi-Fi link must not prevent Bluetooth reconnect. */ }
        }
        try {
            val transport = ble ?: bluetooth() ?: error("StarPilot unavailable. Connect over local Wi-Fi or pair Bluetooth in Connections.")
            return synchronized(transport) {
                val (stamp, body) = latestBody()
                val response = transport.request(JSONObject().put("method", "POST").put("path", path)
                    .put("headers", JSONObject().put("content-type", "application/json"))
                    .put("body", Base64.getEncoder().encodeToString(body)), deadlineSeconds)
                val status = response.getInt("status")
                require(status == 200) { "Galaxy rejected request (HTTP $status)" }
                onResponse?.invoke("Bluetooth", JSONObject(String(Base64.getDecoder().decode(response.getString("body")), Charsets.UTF_8)))
                stamp
            }
        } catch (failure: Exception) {
            Networks.commaLan(context)?.let { return postLocal(it.first, it.second, path, onResponse, latestBody) }
            throw failure
        }
    }
    private fun bluetooth(): BleTransport? = synchronized(BleTransport.connectionLock) {
        val now = SystemClock.elapsedRealtime()
        BleTransport.active?.takeIf { it.open }?.let { return it }
        if (BleTransport.busy || now - lastBluetoothAttempt < 15_000) return null
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        if (context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled != true) return null
        lastBluetoothAttempt = now
        val pairing = PairingStore(context)
        val key = pairing.load()?.takeUnless { pairing.stale() } ?: return null
        val address = pairing.deviceAddress() ?: return null
        val transport = BleTransport(context, key)
        return try { transport.connect(address); own = transport; transport } catch (_: Exception) { transport.close(); null }
    }
    private fun <T> postLocal(network: android.net.Network, origin: String, path: String, onResponse: ((String, JSONObject) -> Unit)?, latestBody: () -> Pair<T, ByteArray>): T {
        if (cookieOrigin != origin) { cookie = ""; cookieOrigin = origin }
        for (attempt in 0..1) {
            if (cookie.isEmpty()) cookie = open(network, origin, "/api/auth/session").run {
                try { getHeaderField("Set-Cookie")?.substringBefore(';') ?: "" } finally { disconnect() }
            }
            val connection = open(network, origin, path)
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val (stamp, body) = latestBody()
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode == 200) {
                    if (onResponse != null) {
                        val raw = ByteArray(4097)
                        val size = connection.inputStream.use { input ->
                            var size = 0
                            while (size < raw.size) {
                                val count = input.read(raw, size, raw.size - size)
                                if (count < 0) break
                                size += count
                            }
                            size
                        }
                        require(size <= 4096) { "Galaxy response too large" }
                        onResponse("Wi-Fi / LAN", JSONObject(String(raw, 0, size, Charsets.UTF_8)))
                    }
                    return stamp
                }
                if (connection.responseCode != 401) error("Galaxy rejected request (HTTP ${connection.responseCode})")
            } finally { connection.disconnect() }
            cookie = ""
        }
        error("Galaxy session unavailable")
    }
    private fun open(network: android.net.Network, origin: String, path: String) =
        (network.openConnection(URL(origin + path)) as HttpURLConnection).apply {
            connectTimeout = 2000; readTimeout = 2000; instanceFollowRedirects = false
            setRequestProperty("Origin", origin)
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }
    override fun close() { own?.close(); own = null }
}
