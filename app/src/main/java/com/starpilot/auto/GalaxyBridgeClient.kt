package com.starpilot.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Client for the version 1 Galaxy IPC contract. All calls run on a car screen worker. */
internal class GalaxyBridgeClient(private val context: Context) : Closeable {
    companion object {
        const val PRIMARY_PACKAGE = "com.starpilot.galaxy"
        const val SERVICE_CLASS = "link.firestar.companion.GalaxyBridgeService"
        const val HELLO = 1
        const val REQUEST = 2
        const val PROTOCOL_VERSION = 1
    }

    @Volatile var lastTransport: String = "Not connected yet"
        private set
    @Volatile var isConnected: Boolean = false
        private set
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            pending.remove(message.arg1)?.complete(message.data)
        }
    })
    private val lock = Object()
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                if (closed || !bound) return
                remote = Messenger(binder)
                lock.notifyAll()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnect()
        override fun onBindingDied(name: ComponentName) = disconnect()
        override fun onNullBinding(name: ComponentName) = disconnect()
    }

    init {
        heartbeat.scheduleWithFixedDelay({
            val target = synchronized(lock) { if (closed) null else remote }
            if (target != null) runCatching {
                target.send(Message.obtain(null, HELLO).apply {
                    data = Bundle().apply { putInt("version", PROTOCOL_VERSION) }
                })
            }.onFailure { disconnect() }
        }, 25, 25, TimeUnit.SECONDS)
    }

    private fun disconnect() {
        synchronized(lock) {
            remote = null
            isConnected = false
            if (bound) {
                bound = false
                runCatching { context.unbindService(connection) }
            }
            lock.notifyAll()
        }
        pending.values.forEach { it.completeExceptionally(IOException("Galaxy app disconnected")) }
        pending.clear()
    }

    private fun service(): Messenger {
        synchronized(lock) {
            check(!closed) { "Galaxy connection closed" }
            if (!bound) {
                requireTrustedPrimary()
                val intent = Intent().setComponent(ComponentName(PRIMARY_PACKAGE, SERVICE_CLASS))
                bound = try { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                catch (_: SecurityException) { false }
                if (!bound) throw IOException("Install or update Galaxy on your phone when parked.")
            }
            val deadline = System.currentTimeMillis() + 3_000
            while (remote == null && System.currentTimeMillis() < deadline) {
                lock.wait(deadline - System.currentTimeMillis())
            }
            return remote ?: throw IOException("Galaxy app is unavailable. Try again shortly.")
        }
    }

    private fun requireTrustedPrimary() {
        val pm = context.packageManager
        val uid = try { pm.getPackageUid(PRIMARY_PACKAGE, 0) }
            catch (_: PackageManager.NameNotFoundException) { throw IOException("Install Galaxy on your phone when parked.") }
        if (pm.checkSignatures(context.packageName, PRIMARY_PACKAGE) == PackageManager.SIGNATURE_MATCH) return
        val pin = BuildConfig.PRIMARY_SIGNER_SHA256
        if (pin.length != 64) throw IOException("Galaxy signing identity is not approved for this companion.")
        val digest = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signed = if (Build.VERSION.SDK_INT >= 28) pm.hasSigningCertificate(PRIMARY_PACKAGE, digest, PackageManager.CERT_INPUT_SHA256)
            else @Suppress("DEPRECATION") runCatching {
                pm.getPackageInfo(PRIMARY_PACKAGE, PackageManager.GET_SIGNATURES).signatures.orEmpty()
                    .any { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).contentEquals(digest) }
            }.getOrDefault(false)
        if (!signed || pm.getPackagesForUid(uid)?.contains(PRIMARY_PACKAGE) != true)
            throw IOException("Galaxy signing identity is not approved for this companion.")
    }

    private fun exchange(what: Int, data: Bundle, timeoutSeconds: Long = 8): Bundle {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<Bundle>()
        pending[id] = future
        try {
            val message = Message.obtain(null, what).apply {
                arg1 = id
                this.data = data
                replyTo = replies
            }
            service().send(message)
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            disconnect()
            if (failure is IOException) throw failure
            throw IOException("Galaxy app did not respond. Try again shortly.", failure)
        } finally { pending.remove(id) }
    }

    fun request(path: String, payload: JSONObject?): JSONObject {
        require(path.startsWith("/api/") && !path.contains("..")) { "Invalid Galaxy request" }
        handshake()
        val result = exchange(REQUEST, Bundle().apply {
            putInt("version", PROTOCOL_VERSION)
            putString("method", if (payload == null) "GET" else "POST")
            putString("path", path)
            if (payload != null) putString("body", payload.toString())
            putString("commandId", java.util.UUID.randomUUID().toString())
        }, 100)
        val error = result.getString("error")
        if (error != null) throw IOException(error)
        lastTransport = result.getString("transport") ?: "Galaxy app"
        return JSONObject(result.getString("body") ?: "{}")
    }

    internal fun handshake(): Int {
        val hello = exchange(HELLO, Bundle().apply { putInt("version", PROTOCOL_VERSION) })
        val version = hello.getInt("version", 0)
        if (version < PROTOCOL_VERSION) throw IOException("Update Galaxy on your phone when parked.")
        isConnected = true
        return version
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            disconnect()
        }
        heartbeat.shutdownNow()
    }
}
