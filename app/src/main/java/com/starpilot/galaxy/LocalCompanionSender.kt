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

internal class CompanionTimingException : IllegalStateException("Refreshing StarPilot clock")

/** A non-200 reply from a reachable comma; anything else means it was unreachable. */
internal class CompanionHttpException(val status: Int) : IllegalStateException("Galaxy rejected request (HTTP $status)")

/** Authenticated, local-only posts shared by GPS, navigation and notifications. Worker threads only. */
internal class LocalCompanionSender(private val context: Context) : Closeable {
    private val clock = CompanionClock()
    private var cookie = ""
    private var cookieOrigin = ""
    private var lastBluetoothAttempt = -15_000L
    private var own: BleTransport? = null

    fun <T> post(path: String, deadlineSeconds: Long = 90, onResponse: ((String, JSONObject) -> Unit)? = null, timingProbe: Boolean = false, latestBody: (Long?) -> Pair<T, ByteArray>): T {
        val ble = BleTransport.active?.takeIf { it.open }
        if (ble == null) Networks.commaLan(context)?.let {
            try { return postLocal(it.first, it.second, path, onResponse, timingProbe, latestBody) }
            catch (_: Exception) { /* A disappearing Wi-Fi link must not prevent Bluetooth reconnect. */ }
        }
        try {
            val transport = ble ?: bluetooth() ?: error("StarPilot unavailable. Connect over local Wi-Fi or pair Bluetooth in Connections.")
            return synchronized(transport) {
                val serverTime = if (timingProbe) calibratedTime(transport) {
                    val reply = transport.request(JSONObject().put("method", "GET").put("path", PhoneGps.PATH)
                        .put("headers", JSONObject()).put("body", ""), 5)
                    if (reply.optInt("status") == 200) JSONObject(String(Base64.getDecoder().decode(reply.getString("body")))).optLong("serverMonoTimeNs") else null
                } else null
                val (stamp, body) = latestBody(serverTime)
                val response = transport.request(JSONObject().put("method", "POST").put("path", path)
                    .put("headers", JSONObject().put("content-type", "application/json"))
                    .put("body", Base64.getEncoder().encodeToString(body)), deadlineSeconds)
                val status = response.getInt("status")
                if (status == 400 && serverTime != null) { clock.clear(); throw CompanionTimingException() }
                if (status != 200) throw CompanionHttpException(status)
                if (onResponse != null || timingProbe) {
                    val reply = JSONObject(String(Base64.getDecoder().decode(response.getString("body")), Charsets.UTF_8))
                    if (timingProbe && !reply.optBoolean("accepted", true) && reply.optString("reason") == "delayed") clock.clear()
                    onResponse?.invoke("Bluetooth", reply)
                }
                stamp
            }
        } catch (failure: Exception) {
            Networks.commaLan(context)?.let { return postLocal(it.first, it.second, path, onResponse, timingProbe, latestBody) }
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
    internal fun <T> postLocal(network: android.net.Network, origin: String, path: String, onResponse: ((String, JSONObject) -> Unit)?, timingProbe: Boolean, latestBody: (Long?) -> Pair<T, ByteArray>): T {
        if (cookieOrigin != origin) { cookie = ""; cookieOrigin = origin }
        for (attempt in 0..1) {
            if (cookie.isEmpty()) cookie = open(network, origin, "/api/auth/session").run {
                try { getHeaderField("Set-Cookie")?.substringBefore(';') ?: "" } finally { disconnect() }
            }
            val clockKey = network to origin
            val serverTime = if (timingProbe) calibratedTime(clockKey) {
                val probe = open(network, origin, PhoneGps.PATH)
                try {
                    if (probe.responseCode == 200) readJson(probe).optLong("serverMonoTimeNs") else null
                } finally { probe.disconnect() }
            } else null
            val connection = open(network, origin, path)
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connect()
                // TCP setup can block. Recompute both the measurement and calibration after it completes.
                val (stamp, body) = latestBody(if (serverTime != null) clock.time(clockKey, SystemClock.elapsedRealtimeNanos()) else null)
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode == 200) {
                    if (onResponse != null || timingProbe) {
                        val reply = readJson(connection)
                        if (timingProbe && !reply.optBoolean("accepted", true) && reply.optString("reason") == "delayed") clock.clear()
                        onResponse?.invoke("Wi-Fi / LAN", reply)
                    }
                    return stamp
                }
                if (connection.responseCode == 400 && serverTime != null) { clock.clear(); throw CompanionTimingException() }
                if (connection.responseCode != 401) throw CompanionHttpException(connection.responseCode)
            } finally { connection.disconnect() }
            cookie = ""
        }
        error("Galaxy session unavailable")
    }
    private fun calibratedTime(key: Any, probe: () -> Long?): Long? {
        val now = SystemClock.elapsedRealtimeNanos()
        if (clock.needsProbe(key, now)) clock.record(key, probe()?.takeIf { it > 0 }, SystemClock.elapsedRealtimeNanos())
        return clock.time(key, SystemClock.elapsedRealtimeNanos())
    }
    private fun readJson(connection: HttpURLConnection): JSONObject {
        val raw = connection.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(1024)
            while (buffer.size() <= 4096) {
                val count = input.read(bytes, 0, minOf(bytes.size, 4097 - buffer.size()))
                if (count < 0) break
                buffer.write(bytes, 0, count)
            }
            buffer.toByteArray()
        }
        require(raw.size <= 4096) { "Galaxy response too large" }
        return JSONObject(String(raw, Charsets.UTF_8))
    }
    private fun open(network: android.net.Network, origin: String, path: String) =
        (network.openConnection(URL(origin + path)) as HttpURLConnection).apply {
            connectTimeout = 2000; readTimeout = 2000; instanceFollowRedirects = false
            setRequestProperty("Origin", origin)
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }
    override fun close() { own?.close(); own = null }
}
