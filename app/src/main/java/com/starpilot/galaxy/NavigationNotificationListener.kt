package com.starpilot.galaxy

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Observes registered navigation apps and, when enabled, forwards message notifications;
 * never cancels notifications or invokes their actions.
 */
class NavigationNotificationListener : NotificationListenerService(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        internal val diagnostics = NavigationDiagnostics { SystemClock.elapsedRealtime() }
        const val PATH = "/api/companion/navigation"
        private const val MAX_AGE_MS = 90_000L
        private const val ICON_PX = 64
        fun preferences(context: Context) = context.getSharedPreferences("mapsNavigation", Context.MODE_PRIVATE)
        fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, NavigationNotificationListener::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 27) return context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
            return android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }
        fun refresh(context: Context) = requestRebind(ComponentName(context, NavigationNotificationListener::class.java))
    }
    private data class Observation(val sequence: Long, val stamp: Long, val guidance: NavigationGuidance?, val source: String = "")
    private val parsers = NavigationNotificationParsers.DEFAULT
    private val session = UUID.randomUUID().toString()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var sequence = 0L
    private val order = NavigationObservationOrder()
    private val transmitQueued = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var key: String? = null
    private var selectedPostTime = 0L
    @Volatile private var connected = false
    @Volatile private var latest = Observation(0, SystemClock.elapsedRealtime(), null)
    private var sent = -1L
    private var sentContent = ""
    private var lastSend = 0L
    // Worker only: older StarPilot rejects the trip-total fields with HTTP 400.
    private var legacyPayload = false
    @Volatile private var destroyed = false
    private val sender by lazy { LocalCompanionSender(this) }
    // Arrow shape fingerprints learned from notifications that also named the turn in text.
    private val icons by lazy {
        val prefs = getSharedPreferences("mapsNavigationIcons", Context.MODE_PRIVATE)
        NavigationIconMemory(prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()) { put, removed ->
            prefs.edit().apply { put.forEach { (key, value) -> putString(key, value) }; removed.forEach { remove(it) } }.apply()
        }
    }
    private val forwarder by lazy { PhoneNotificationForwarder(this) }
    private val bluetoothConnected = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { forwarder.reachable(); drainSoon() }
    }

    override fun onCreate() {
        super.onCreate()
        preferences(this).registerOnSharedPreferenceChangeListener(this)
        worker.scheduleWithFixedDelay({ runCatching { transmit() }; runCatching { drain() } }, 1, 1, TimeUnit.SECONDS)
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothConnected, filter, RECEIVER_EXPORTED)
            else registerReceiver(bluetoothConnected, filter)
        }
    }
    private fun drain() { if (!destroyed) forwarder.drain(sender) }
    private fun drainSoon() { runCatching { worker.execute { runCatching { drain() } } } }
    private fun transmitSoon() {
        if (!destroyed && transmitQueued.compareAndSet(false, true))
            runCatching { worker.execute { transmitQueued.set(false); runCatching { transmit() } } }.onFailure { transmitQueued.set(false) }
    }
    override fun onListenerConnected() {
        connected = true
        diagnostics.listener(true)
        scan()
    }
    private fun scan(excludeKey: String? = null) {
        if (!enabled(this)) return clear("Forwarding disabled")
        val notifications = runCatching { activeNotifications.toList() }.getOrElse { clear(); return }
        val maps = notifications.filter { parsers.forPackage(it.packageName) != null && it.isOngoing && it.key != excludeKey }
        if (maps.isEmpty()) clear()
        else maps.sortedByDescending { it.postTime }.forEach { onNotificationPosted(it) }
    }
    // scan() replays active navigation through the one-argument overload only, so old messages are never forwarded.
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (parsers.forPackage(sbn.packageName) == null) { if (forwarder.posted(sbn, rankingMap)) drainSoon(); return }
        onNotificationPosted(sbn)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val parser = parsers.forPackage(sbn.packageName) ?: return
        if (!enabled(this)) { clear("Forwarding disabled"); return }
        if (!order.accepts(sbn.key, sbn.postTime) || sbn.key == key && sbn.postTime < selectedPostTime) return
        if (!sbn.isOngoing) {
            onNotificationRemoved(sbn)
            return
        }
        val age = System.currentTimeMillis() - sbn.postTime
        if (age !in 0..MAX_AGE_MS) { if (sbn.key == key) clear(); return }
        val extras = sbn.notification.extras
        fun field(name: String) = runCatching { extras.getCharSequence(name)?.toString()?.take(500) }.getOrNull().orEmpty()
        // Only adapters that need custom text fields request view inspection.
        val viewFields = mutableMapOf<String, String>()
        var image: Drawable? = null
        var imageArea = 0
        var imageName = ""
        if (parser.viewFields.isNotEmpty()) runCatching {
            val builder = Notification.Builder.recoverBuilder(this, sbn.notification)
            val view = builder.createBigContentView() ?: builder.createContentView()
            fun visit(node: View, depth: Int = 0) {
                if (depth > 12) return
                if (node is TextView && node.id != View.NO_ID) {
                    val name = runCatching { node.resources.getResourceEntryName(node.id) }.getOrNull()
                    if (name in parser.viewFields)
                        viewFields[name!!] = node.text.toString().take(500)
                }
                // The largest image is the current maneuver arrow; "icon" is the app's small icon.
                if (node is ImageView && node.visibility == View.VISIBLE) node.drawable?.let { drawable ->
                    val name = if (node.id == View.NO_ID) "" else runCatching { node.resources.getResourceEntryName(node.id) }.getOrNull().orEmpty()
                    val area = drawable.intrinsicWidth * drawable.intrinsicHeight
                    if (name != "icon" && area > imageArea) { image = drawable; imageArea = area; imageName = name.ifEmpty { "image" } }
                }
                if (node is ViewGroup) for (i in 0 until minOf(node.childCount, 64)) visit(node.getChildAt(i), depth + 1)
            }
            visit(view.apply(this, null))
        }
        val text = NavigationNotificationText(field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT),
            field(Notification.EXTRA_BIG_TEXT), runCatching {
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.take(16)?.map { it.toString().take(500) }
            }.getOrNull().orEmpty(), viewFields, field(Notification.EXTRA_SUB_TEXT))
        val large = runCatching { sbn.notification.getLargeIcon()?.loadDrawable(this) }.getOrNull()
        val arrow = large ?: image
        val hash = arrow?.let { runCatching { NavigationIconFingerprint.of(pixels(it), ICON_PX, ICON_PX) }.getOrNull() }
        val (guidance, iconResult) = icons.resolve(parser.source, hash, runCatching { parser.parse(text) }.getOrNull())
        diagnostics.icon(if (large != null) "large icon" else if (image != null) "view $imageName" else "none", hash, iconResult)
        synchronized(lock) {
            // An unrelated/older ongoing Maps notification must not replace
            // selected guidance. Incomplete updates of the selected key clear it.
            val ignored = key != null && parsers.priority(parser.source) > parsers.priority(latest.source) ||
                latest.source == parser.source && sbn.postTime < selectedPostTime || guidance == null && sbn.key != key
            if (ignored) {
                diagnostics.detected(parser.source, text, guidance, SystemClock.elapsedRealtime() - age, selected = false)
                return
            }
            diagnostics.detected(parser.source, text, guidance, SystemClock.elapsedRealtime() - age)
            selectedPostTime = sbn.postTime
            key = sbn.key
            latest = Observation(++sequence, SystemClock.elapsedRealtime() - age, guidance, parser.source)
        }
        transmitSoon()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (parsers.forPackage(sbn.packageName) == null) return
        order.removed(sbn.key, sbn.postTime, System.currentTimeMillis() - MAX_AGE_MS)
        if (sbn.key == key && sbn.postTime >= selectedPostTime) { clear("Navigation notification ended"); scan(excludeKey = sbn.key) }
    }
    override fun onListenerDisconnected() { connected = false; diagnostics.listener(false); clear("Listener disconnected") }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String?) {
        if (name == "enabled") scan()
        if (name == PhoneNotificationForwarder.ENABLED && !PhoneNotificationForwarder.enabled(this)) forwarder.clear()
    }
    /** [only] clears just that observation, so a check on the worker cannot wipe guidance posted meanwhile. */
    private fun clear(reason: String = "No active navigation", only: Observation? = null) = synchronized(lock) {
        if (only != null && latest !== only) return@synchronized
        diagnostics.cleared(reason)
        if (key == null && latest.guidance == null) return@synchronized
        key = null
        selectedPostTime = 0L
        latest = Observation(++sequence, SystemClock.elapsedRealtime(), null, latest.source)
        transmitSoon()
    }
    private fun transmit() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val checked = latest
        if (checked.guidance != null) when {
            !connected -> clear("Listener disconnected", checked)
            !enabled(this) -> clear("Forwarding disabled", checked)
            !hasAccess(this) -> clear("Notification access revoked", checked)
            now - checked.stamp > MAX_AGE_MS -> clear("Observation expired", checked)
        }
        val observation = latest
        val guidance = observation.guidance
        // Suppress identical content until its lease heartbeat is due.
        val fingerprint = if (guidance == null) "" else observation.source + ":" + guidance
        if (guidance == null && sentContent == "") return
        if (observation.sequence == sent && (guidance == null || now - lastSend < 5_000)) return
        if (fingerprint == sentContent && now - lastSend < 5_000) return
        var extended = false
        try {
            val delivered = sender.post(PATH, deadlineSeconds = 10, onResponse = { transport, response ->
                diagnostics.delivered(transport, response.optBoolean("accepted", true),
                    if (response.has("forwardingEnabled")) response.optBoolean("forwardingEnabled") else null)
            }) {
                // Connections and Bluetooth locks can take time. Date the newest
                // observation only when the selected transport is ready to send.
                check(!destroyed)
                val pending = latest
                if (pending.guidance != null && (!connected || !enabled(this) || !hasAccess(this) ||
                    SystemClock.elapsedRealtime() - pending.stamp > MAX_AGE_MS)) clear(only = pending)
                extended = false
                val current = latest
                val data = current.guidance
                val payload = JSONObject().put("source", current.source).put("session", session).put("sequence", current.sequence)
                    .put("active", data != null).put("ageMs", if (data == null) 0 else
                        (SystemClock.elapsedRealtime() - current.stamp).coerceAtLeast(0))
                if (data != null) {
                    payload.put("instruction", data.instruction).put("distanceMeters", data.distanceMeters ?: JSONObject.NULL)
                        .put("maneuverType", data.maneuverType).put("maneuverModifier", data.modifier)
                    data.roadName?.let { payload.put("roadName", it) }
                    data.exitNumber?.let { payload.put("exitNumber", it) }
                    data.destination?.let { payload.put("destination", it) }
                    if (!legacyPayload) {
                        data.remainingMeters?.takeIf { it.isFinite() && it in 0.0..20_000_000.0 }?.let { payload.put("remainingMeters", it); extended = true }
                        data.remainingSeconds?.takeIf { it in 0L..604_800L }?.let { payload.put("remainingSeconds", it); extended = true }
                    }
                }
                current to payload.toString().toByteArray()
            }
            sent = delivered.sequence; sentContent = if (delivered.guidance == null) "" else delivered.source + ":" + delivered.guidance
            lastSend = SystemClock.elapsedRealtime()
            forwarder.reachable()
        } catch (failure: Exception) {
            if (failure is CompanionHttpException && failure.status == 400 && extended && !legacyPayload) {
                legacyPayload = true
                diagnostics.tripTotalsUnsupported()
                return transmit()
            }
            diagnostics.failed(failure.message ?: failure.javaClass.simpleName)
        }
    }
    private fun pixels(drawable: Drawable): IntArray {
        val copy = drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, ICON_PX, ICON_PX)
        copy.draw(Canvas(bitmap))
        return IntArray(ICON_PX * ICON_PX).also { bitmap.getPixels(it, 0, ICON_PX, 0, 0, ICON_PX, ICON_PX); bitmap.recycle() }
    }
    override fun onDestroy() {
        destroyed = true
        connected = false
        diagnostics.listener(false)
        diagnostics.cleared("Listener stopped; receiver lease expires")
        preferences(this).unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(bluetoothConnected) }
        // Close on the worker after any in-flight post, so a Bluetooth link opened by that post is not leaked.
        runCatching { worker.execute { runCatching { sender.close() } } }
        worker.shutdown()
        super.onDestroy()
        // The receiver's 15-second lease also covers process death/revoked access.
    }
}
