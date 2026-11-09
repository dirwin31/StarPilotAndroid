package com.starpilot.galaxy

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** comma refused GATT access: it has no paired phones, or not this one. */
class GattDenied(status: Int) : IOException("Bluetooth access denied ($status)")
/** The link works but comma never answered the authenticated check: it no longer knows this phone's key. */
class PairingNotRecognized(message: String) : IOException(message)
private class ResponseTimeout : IOException("Bluetooth response timed out. Check the result before retrying a change.")

/** Serialized GATT operations; a failed mutation is never replayed. Call from a worker thread. */
@SuppressLint("MissingPermission")
/** [hello] ({"name", "replaces"}) is sent once with the first request so comma can label this phone and drop its old key. */
class BleTransport(context: Context, private val key: ByteArray?, private val hello: JSONObject? = null) : Closeable {
    /** Reports what the connection is doing, so a slow pairing shows where it waits. */
    @Volatile var onPhase: (String) -> Unit = {}
    private fun phase(message: String) {
        DiagnosticLog.bluetoothPhase = message
        DiagnosticLog.record("BLE: $message")
        onPhase(message)
    }
    private val context: Context = context.applicationContext
    companion object {
        /** Serializes connection creation across the phone UI and the car bridge. */
        val connectionLock = Any()
        fun uuid(part: String): UUID = UUID.fromString("bd49000$part-6dc1-4de7-a7d0-6cdb441f7650")
        // comma keeps one session per phone, so every sender shares the newest connection.
        @SuppressLint("StaticFieldLeak") @Volatile var active: BleTransport? = null
            private set
        private val connecting = java.util.concurrent.atomic.AtomicInteger()
        val busy get() = connecting.get() > 0
    }
    val open get() = !closed && session.isNotEmpty()
    /** Diagnostics distinguish an authenticated session from a link Android has already disconnected. */
    val linkActive get() = open && linkConnected
    @Volatile private var linkConnected = false
    private data class Event(val kind: String, val status: Int, val data: ByteArray = byteArrayOf())
    private val events = LinkedBlockingQueue<Event>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var session = ""
    private var challenge = ByteArray(0)
    private var counter = 0L
    private var packetSize = 20
    private var mtuNegotiated = false
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            linkConnected = !closed && status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED
            DiagnosticLog.record("BLE GATT connection state=$state status=$status expectedClose=$closed")
            if (!closed && (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED))
                DiagnosticLog.failure("BLE link disconnected: state=$state GATT status=$status")
            events.offer(Event(if (state == BluetoothProfile.STATE_CONNECTED) "connect" else "disconnect", status))
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            DiagnosticLog.record("BLE MTU=$mtu GATT status=$status")
            events.offer(Event("mtu", status, ByteBuffer.allocate(4).putInt(mtu).array()))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            DiagnosticLog.record("BLE service discovery GATT status=$status")
            events.offer(Event("services", status))
        }
        @Deprecated("Pre Android 13 callback") override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) events.offer(Event("read", status, c.value ?: byteArrayOf()))
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.offer(Event("read", status, value.copyOf()))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.offer(Event("write", status)) }
    }
    private fun await(kind: String, seconds: Long = 12): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: run {
                DiagnosticLog.failure("BLE timeout waiting for $kind (${seconds}s)")
                throw IOException("Bluetooth timed out. Reconnect before retrying; a change may already have applied.")
            }
            // Android 14+ negotiates the MTU on its own as soon as the link is up, so it can land before service discovery finishes.
            if (!closed && event.kind == "mtu" && kind != "mtu") { applyMtu(event); continue }
            if (!closed && event.kind == kind && event.status in setOf(5, 8, 15)) {
                DiagnosticLog.failure("BLE $kind access denied: GATT status=${event.status}")
                throw GattDenied(event.status)
            }
            if (closed || event.kind != kind || event.status != BluetoothGatt.GATT_SUCCESS) {
                DiagnosticLog.failure("BLE waiting for $kind: received=${event.kind} GATT status=${event.status} closed=$closed")
                throw IOException("Bluetooth disconnected (${event.kind}, status ${event.status}). Check the result before retrying a change.")
            }
            return event.data
        }
    }
    private fun applyMtu(event: Event) {
        if (event.status != BluetoothGatt.GATT_SUCCESS) return
        packetSize = (ByteBuffer.wrap(event.data).int - 3).coerceIn(20, 512)
        mtuNegotiated = true
    }
    private fun characteristic(part: String) = gatt?.getService(uuid("1"))?.getCharacteristic(uuid(part)) ?: throw IOException("Galaxy Bluetooth service unavailable")
    private fun read(part: String): ByteArray {
        if (gatt?.readCharacteristic(characteristic(part)) != true) throw IOException("Bluetooth read unavailable")
        return await("read")
    }
    private fun write(value: ByteArray) {
        val g = gatt ?: throw IOException("Bluetooth disconnected")
        val c = characteristic("2")
        val sent = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = value; g.writeCharacteristic(c) }
        if (!sent) throw IOException("Bluetooth write unavailable")
        await("write")
    }
    /** Returns the authenticated device address so the next connection can skip scanning. */
    @Synchronized fun connect(preferredAddress: String? = null): String {
        return synchronized(connectionLock) {
            check(!closed)
            connecting.incrementAndGet()
            try { connectOnce(preferredAddress) } finally { connecting.decrementAndGet() }
        }
    }
    private fun connectOnce(preferredAddress: String?): String {
        DiagnosticLog.record("BLE connect: rememberedDevice=${preferredAddress != null}")
        val device = try { linkUp(preferredAddress) } catch (e: GattDenied) {
            throw PairingNotRecognized("StarPilot has no paired phone matching this one")
        }
        try {
            // Authenticate before exposing cached controls. Do not trust the advertised device name.
            val health = JSONObject().put("method", "GET").put("path", "/_bridge/health").apply { hello?.let { put("phone", it) } }
            phase("Authenticating the saved app pairing…")
            val response = try { request(health, 20) } catch (e: ResponseTimeout) {
                throw PairingNotRecognized("StarPilot didn't recognize this phone's pairing")
            }
            if (response.getInt("status") != 200) {
                DiagnosticLog.failure("BLE authentication HTTP ${response.optInt("status")}")
                throw IOException("Galaxy Bluetooth authentication failed")
            }
            phase("App pairing authenticated")
            val previous = active
            active = this
            if (previous !== this) previous?.close()
            PendingShare.deliver(context)
            return device.address
        } catch (e: Exception) { close(); throw e }
    }
    /** Scans, connects and reads the session challenge; nothing is authenticated yet. */
    private fun linkUp(preferredAddress: String?): BluetoothDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw IOException("Enable Bluetooth on your phone and StarPilot")
        val remembered = preferredAddress?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        val device = remembered ?: run {
            phase("Looking for StarPilot over Bluetooth…")
            val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth scanner unavailable")
            val found = LinkedBlockingQueue<BluetoothDevice>()
            val scan = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) { found.offer(result.device) }
                override fun onScanFailed(code: Int) {
                    DiagnosticLog.failure("BLE scan failed: Android code=$code")
                    events.offer(Event("scan-failed", code))
                }
            }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid("1"))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            try { found.poll(12, TimeUnit.SECONDS) } finally { scanner.stopScan(scan) }
                ?: throw IOException("No Galaxy Bluetooth bridge found. Enable Bluetooth and install the bridge on StarPilot.")
        }
        if (closed) throw IOException("Connection canceled")
        events.clear()
        phase("Found StarPilot. Connecting…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            await("connect", if (remembered != null) 4 else 12)
            phase("Checking StarPilot’s Bluetooth service…")
            if (gatt?.discoverServices() != true) throw IOException("Bluetooth discovery failed")
            await("services")
            events.firstOrNull { it.kind == "mtu" }?.let { events.remove(it); applyMtu(it) }
            if (!mtuNegotiated && gatt?.requestMtu(517) == true) packetSize = (ByteBuffer.wrap(await("mtu")).int - 3).coerceIn(20, 512)
            DiagnosticLog.record("BLE negotiated payload bytes=$packetSize")
            phase("Reading StarPilot’s secure pairing challenge…")
            val info = read("4")
            require(info.size == 17 && info[0].toInt() == 1)
            challenge = info.copyOfRange(1, 17)
            session = info.drop(1).joinToString("") { "%02x".format(it.toInt() and 255) }
            return device
        } catch (e: Exception) { close(); throw e }
    }
    /** Asks the comma whose pairing window is open for a key. Returns the session challenge and comma's reply. */
    @Synchronized fun claim(body: ByteArray): Pair<ByteArray, ByteArray> {
        check(!closed)
        connecting.incrementAndGet()
        try {
            try { linkUp(null) } catch (e: GattDenied) {
                throw IOException("StarPilot's pairing window isn't open. Open Pair phone on StarPilot first (Settings > Bluetooth > Pair phone), then try again.")
            }
            phase("Pairing: sending this phone’s request to StarPilot…")
            val frame = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
            for (packet in BleWire.packets(frame, packetSize)) write(packet)
            phase("Pairing: waiting for StarPilot’s secure reply…")
            val assembler = BleWire.Assembler()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val packet = read("3")
                if (packet.contentEquals(byteArrayOf(0))) { Thread.sleep(30); continue }
                val result = assembler.add(packet)
                if (result != null) {
                    phase("Pairing: receiving StarPilot’s secure reply…")
                    write(ByteBuffer.allocate(5).put(2).put(packet, 1, 4).array())
                    return challenge to result
                }
            }
            throw IOException("StarPilot didn't answer. Open Pair phone on StarPilot first (Settings > Bluetooth > Pair phone) and keep it open, then tap Add Bluetooth Phone Pairing again.")
        } catch (e: Exception) { close(); throw e } finally { connecting.decrementAndGet() }
    }
    @Synchronized fun request(value: JSONObject, deadlineSeconds: Long = 90): JSONObject {
        val diagnosticEpoch = DiagnosticLog.epoch()
        check(!closed && session.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val count = ++counter
        val request = JSONObject(value.toString()).put("id", id).put("session", session).put("counter", count).put("responseCodec", 2).put("responseFlow", "read-stream")
        // Upstream read-stream flow acknowledges once after authenticated reassembly.
        try {
            for (packet in BleWire.packets(BleWire.seal(request.toString().toByteArray(), key ?: throw IOException("Not paired")), packetSize)) write(packet)
            val assembler = BleWire.Assembler()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
            while (System.nanoTime() < deadline) {
                val packet = read("3")
                if (packet.contentEquals(byteArrayOf(0))) { Thread.sleep(30); continue }
                val result = assembler.add(packet)
                if (result != null) {
                    val decoded = BleWire.decode(result, key ?: throw IOException("Not paired"))
                    val response = JSONObject(String(decoded.json, Charsets.UTF_8))
                    decoded.body?.let { require(!response.has("body")); response.put("body", java.util.Base64.getEncoder().encodeToString(it)) }
                    require(response.getString("id") == id && response.getString("session") == session && response.getLong("counter") == count)
                    write(ByteBuffer.allocate(5).put(2).put(packet, 1, 4).array())
                    if (value.optString("method") == "GET" && value.optString("path").substringBefore('?') == "/api/software/status" && response.optInt("status") == 200) {
                        runCatching {
                            DiagnosticLog.observeSoftware(JSONObject(String(java.util.Base64.getDecoder().decode(response.getString("body")), Charsets.UTF_8)), "BLUETOOTH", diagnosticEpoch)
                        }
                    }
                    return response
                }
            }
            DiagnosticLog.failure("BLE authenticated reply timed out (${deadlineSeconds}s)")
            throw ResponseTimeout()
        } catch (e: Exception) {
            DiagnosticLog.record("BLE request failed: ${e.javaClass.simpleName}")
            close(); throw e
        }
    }
    override fun close() {
        closed = true
        linkConnected = false
        if (active === this) active = null
        val old = gatt; gatt = null
        old?.disconnect(); old?.close()
        events.offer(Event("disconnect", -1))
    }
}
