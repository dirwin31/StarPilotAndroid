package link.firestar.companion

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Base64
import com.starpilot.galaxy.BleTransport
import com.starpilot.galaxy.BuildConfig
import com.starpilot.galaxy.PairingStore
import com.starpilot.galaxy.SentryApi
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** Phone-owned endpoint for the independent Android Auto app. No credentials cross IPC. */
class GalaxyBridgeService : Service() {
    companion object {
        private const val COMPANION = "com.starpilot.auto"
        private const val HELLO = 1
        private const val REQUEST = 2
        private const val VERSION = 1
        private val CAR_SERVICE = ComponentName(COMPANION, "com.starpilot.auto.GalaxyCarAppService")
        @Volatile private var instance: GalaxyBridgeService? = null

        fun status(context: Context): String {
            val pm = context.packageManager
            val installed = runCatching { pm.getPackageUid(COMPANION, 0) }.isSuccess
            val compatible = installed && runCatching {
                pm.getServiceInfo(CAR_SERVICE, PackageManager.GET_META_DATA).metaData
                    ?.getInt("com.starpilot.auto.bridge.version", 0) ?: 0
            }.getOrDefault(0) >= VERSION
            val trusted = installed && trustedUid(context, pm.getPackageUid(COMPANION, 0))
            val connected = trusted && instance?.let { it.lastSeen > 0 && SystemClock.elapsedRealtime() - it.lastSeen < 60_000 } == true
            return JSONObject().put("installed", installed).put("compatible", compatible)
                .put("trusted", trusted).put("connected", connected).toString()
        }

        private fun trustedUid(context: Context, uid: Int): Boolean {
            if (uid < 0 || context.packageManager.getPackagesForUid(uid)?.contains(COMPANION) != true) return false
            val pm = context.packageManager
            if (pm.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH) return true
            val pin = BuildConfig.AA_COMPANION_CERT_SHA256
            if (pin.length != 64) return false
            val digest = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return if (Build.VERSION.SDK_INT >= 28) pm.hasSigningCertificate(COMPANION, digest, PackageManager.CERT_INPUT_SHA256)
            else @Suppress("DEPRECATION") runCatching {
                pm.getPackageInfo(COMPANION, PackageManager.GET_SIGNATURES).signatures.orEmpty()
                    .any { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).contentEquals(digest) }
            }.getOrDefault(false)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val seenWrites = LinkedHashMap<String, Bundle>()
    private var ownedBluetooth: BleTransport? = null
    private var lastTransport = "Galaxy app"
    private var pendingWriteApi: SentryApi? = null
    private var pendingWriteViaBle = false
    private val viewApis = LinkedHashMap<String, SentryApi?>()
    @Volatile private var lastSeen = 0L
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val uid = message.sendingUid
            if (!trustedUid(this@GalaxyBridgeService, uid)) {
                reply(message, Bundle().apply { putString("error", "Android Auto companion is not authorized") })
                return
            }
            lastSeen = SystemClock.elapsedRealtime()
            when (message.what) {
                HELLO -> reply(message, Bundle().apply { putInt("version", VERSION) })
                REQUEST -> {
                    val receiver = message.replyTo
                    val id = message.arg1
                    val input = Bundle(message.data)
                    worker.execute {
                        val output = runCatching { handleRequest(input) }.getOrElse { failure ->
                            Bundle().apply { putString("error", failure.message?.take(180) ?: "Galaxy request failed") }
                        }
                        runCatching { receiver?.send(Message.obtain(null, REQUEST).apply { arg1 = id; data = output }) }
                    }
                }
                else -> reply(message, Bundle().apply { putString("error", "Unsupported command") })
            }
        }
    })

    private fun reply(request: Message, data: Bundle) {
        runCatching { request.replyTo?.send(Message.obtain(null, request.what).apply { arg1 = request.arg1; this.data = data }) }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { lastSeen = 0L; return false }
    override fun onDestroy() {
        instance = null
        worker.shutdownNow()
        ownedBluetooth?.close()
        super.onDestroy()
    }

    private fun handleRequest(input: Bundle): Bundle {
        require(input.getInt("version") == VERSION) { "Incompatible Android Auto companion" }
        val method = input.getString("method") ?: ""
        val path = input.getString("path") ?: ""
        require(GalaxyBridgePolicy.allowed(method, path)) { "Action unavailable in Android Auto" }
        val write = method == "POST"
        val raw = input.getString("body")
        require(raw == null || raw.length <= 8_192) { "Request too large" }
        val body = if (method == "POST") JSONObject(raw ?: throw IllegalArgumentException("Missing body")) else null
        if (write) {
            require(body != null && GalaxyBridgePolicy.validBody(path, body)) { "Invalid car action" }
            val commandId = input.getString("commandId") ?: ""
            require(runCatching { UUID.fromString(commandId) }.isSuccess) { "Invalid command ID" }
            seenWrites[commandId]?.let { return Bundle(it) }
            val result = Bundle().apply {
                putString("body", route(path, body).toString())
                putString("transport", lastTransport)
            }
            seenWrites[commandId] = result
            if (seenWrites.size > 128) seenWrites.remove(seenWrites.keys.first())
            return result
        }
        return Bundle().apply {
            putString("body", route(path, null).toString())
            putString("transport", lastTransport)
        }
    }

    private fun rememberView(result: JSONObject, api: SentryApi?) {
        val view = result.optString("view")
        if (view.isBlank()) return
        viewApis[view] = api
        if (viewApis.size > 64) viewApis.remove(viewApis.keys.first())
    }

    private fun route(path: String, body: JSONObject?): JSONObject {
        if (path == "/api/settings/preview") {
            pendingWriteApi = null
            pendingWriteViaBle = false
        }
        val view = body?.optString("view").orEmpty()
        val knownBleView = path == "/api/settings/preview" && viewApis.containsKey(view) && viewApis[view] == null
        val useBle = knownBleView || (path == "/api/settings/confirm" && pendingWriteViaBle)
        var networkTransport = "Remote tunnel"
        val networkFailure = runCatching {
            val api = when {
                useBle -> null
                path == "/api/settings/preview" && viewApis.containsKey(view) -> viewApis[view]
                path == "/api/settings/confirm" -> pendingWriteApi ?: SentryApi.best(this)
                else -> SentryApi.best(this)
            }
            api?.let {
                networkTransport = if (it.origin.startsWith("http://")) "Wi-Fi / LAN" else "Remote tunnel"
                it.request(path, body).also { result ->
                    if (path.startsWith("/api/settings/pages/")) rememberView(result, it)
                    if (path == "/api/settings/preview") pendingWriteApi = it
                }
            }
        }
        if (path == "/api/settings/confirm") {
            pendingWriteApi = null
            pendingWriteViaBle = false
        }
        networkFailure.getOrNull()?.let {
            lastTransport = networkTransport
            return it
        }
        if (body != null && networkFailure.exceptionOrNull() != null) throw networkFailure.exceptionOrNull()!!
        val transport = synchronized(BleTransport.connectionLock) {
            BleTransport.active?.takeIf { it.open } ?: run {
                if (BleTransport.busy) throw IOException("Phone is connecting to Galaxy")
                if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED))
                    throw IOException("Allow Nearby devices in Galaxy on your phone")
                val pairing = PairingStore(this)
                val key = pairing.load()?.takeUnless { pairing.stale() } ?: throw IOException("Connect Galaxy on your phone")
                BleTransport(this, key, JSONObject().put("name", "Android phone")).also {
                    try { pairing.rememberDevice(it.connect(pairing.deviceAddress())); ownedBluetooth = it }
                    catch (failure: Exception) { it.close(); throw failure }
                }
            }
        }
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val command = JSONObject().put("method", if (body == null) "GET" else "POST")
            .put("path", path).put("headers", JSONObject().put("Content-Type", "application/json"))
            .put("body", if (bytes == null) "" else Base64.encodeToString(bytes, Base64.NO_WRAP))
        val response = transport.request(command)
        val json = JSONObject(String(Base64.decode(response.optString("body"), Base64.DEFAULT), Charsets.UTF_8))
        if (response.optInt("status") != 200) throw IOException(json.optString("error", "Galaxy request failed"))
        if (path.startsWith("/api/settings/pages/")) rememberView(json, null)
        if (path == "/api/settings/preview") pendingWriteViaBle = true
        lastTransport = "Bluetooth"
        return json
    }
}
