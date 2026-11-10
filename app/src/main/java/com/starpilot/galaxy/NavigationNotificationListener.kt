package com.starpilot.galaxy

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Observes registered navigation apps; never cancels notifications or invokes their actions. */
class NavigationNotificationListener : NotificationListenerService(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        internal val diagnostics = NavigationDiagnostics { SystemClock.elapsedRealtime() }
        const val PATH = "/api/companion/navigation"
        private const val MAX_AGE_MS = 90_000L
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
    @Volatile private var key: String? = null
    private var selectedPostTime = 0L
    @Volatile private var connected = false
    @Volatile private var latest = Observation(0, SystemClock.elapsedRealtime(), null)
    private var sent = -1L
    private var lastSend = 0L
    private val sender by lazy { LocalCompanionSender(this) }

    override fun onCreate() {
        super.onCreate()
        preferences(this).registerOnSharedPreferenceChangeListener(this)
        worker.scheduleWithFixedDelay({ runCatching { transmit() } }, 1, 1, TimeUnit.SECONDS)
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
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val parser = parsers.forPackage(sbn.packageName) ?: return
        if (!enabled(this)) { clear("Forwarding disabled"); return }
        if (!sbn.isOngoing) {
            if (sbn.key == key) { clear("Navigation notification ended"); scan(excludeKey = sbn.key) }
            return
        }
        val age = System.currentTimeMillis() - sbn.postTime
        if (age !in 0..MAX_AGE_MS) { if (sbn.key == key) clear(); return }
        val extras = sbn.notification.extras
        fun field(name: String) = runCatching { extras.getCharSequence(name)?.toString()?.take(500) }.getOrNull().orEmpty()
        // Only adapters that need custom text fields request view inspection.
        val viewFields = mutableMapOf<String, String>()
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
                if (node is ViewGroup) for (i in 0 until minOf(node.childCount, 64)) visit(node.getChildAt(i), depth + 1)
            }
            visit(view.apply(this, null))
        }
        val text = NavigationNotificationText(field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT),
            field(Notification.EXTRA_BIG_TEXT), runCatching {
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.take(16)?.map { it.toString().take(500) }
            }.getOrNull().orEmpty(), viewFields)
        val guidance = runCatching { parser.parse(text) }.getOrNull()
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
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == key) { clear("Navigation notification ended"); scan(excludeKey = sbn.key) }
    }
    override fun onListenerDisconnected() { connected = false; diagnostics.listener(false); clear("Listener disconnected") }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String?) { if (name == "enabled") scan() }
    private fun clear(reason: String = "No active navigation") = synchronized(lock) {
        diagnostics.cleared(reason)
        if (key == null && latest.guidance == null) return@synchronized
        key = null
        selectedPostTime = 0L
        latest = Observation(++sequence, SystemClock.elapsedRealtime(), null, latest.source)
    }
    private fun transmit() {
        val now = SystemClock.elapsedRealtime()
        if (latest.guidance != null) when {
            !connected -> clear("Listener disconnected")
            !enabled(this) -> clear("Forwarding disabled")
            !hasAccess(this) -> clear("Notification access revoked")
            now - latest.stamp > MAX_AGE_MS -> clear("Observation expired")
        }
        val observation = latest
        val guidance = observation.guidance
        // Suppress identical content until its lease heartbeat is due.
        val fingerprint = if (guidance == null) "" else observation.source + ":" + guidance
        if (guidance == null && sentContent == "") return
        if (observation.sequence == sent && (guidance == null || now - lastSend < 5_000)) return
        if (fingerprint == sentContent && now - lastSend < 5_000) return
        try {
            val delivered = sender.post(PATH, deadlineSeconds = 10, onResponse = { transport, response ->
                diagnostics.delivered(transport, response.optBoolean("accepted", true),
                    if (response.has("forwardingEnabled")) response.optBoolean("forwardingEnabled") else null)
            }) {
                // Connections and Bluetooth locks can take time. Date the newest
                // observation only when the selected transport is ready to send.
                if (latest.guidance != null && (!enabled(this) || !hasAccess(this) ||
                    SystemClock.elapsedRealtime() - latest.stamp > MAX_AGE_MS)) clear()
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
                }
                current to payload.toString().toByteArray()
            }
            sent = delivered.sequence; sentContent = if (delivered.guidance == null) "" else delivered.source + ":" + delivered.guidance
            lastSend = SystemClock.elapsedRealtime()
        } catch (failure: Exception) { diagnostics.failed(failure.message ?: failure.javaClass.simpleName) }
    }
    private var sentContent = ""
    override fun onDestroy() {
        connected = false
        diagnostics.listener(false)
        diagnostics.cleared("Listener stopped; receiver lease expires")
        preferences(this).unregisterOnSharedPreferenceChangeListener(this)
        worker.shutdownNow()
        sender.close()
        super.onDestroy()
        // The receiver's 15-second lease also covers process death/revoked access.
    }
}
