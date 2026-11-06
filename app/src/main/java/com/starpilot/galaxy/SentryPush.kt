package com.starpilot.galaxy

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object SentryPush {
    @Volatile var suspended = false
    @Volatile var api: SentryApi? = null
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
    fun permitted(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun state(ctx: Context): String {
        val p = prefs(ctx)
        val id = p.getString("fcmToken", "").orEmpty().takeIf { it.isNotEmpty() }?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }.take(12)
        }.orEmpty()
        return JSONObject().put("id", id).put("available", FirebaseRegistration.available).put("enabled", p.getBoolean("sentryPushEnabled", false))
            .put("registered", p.getString("fcmRegisteredOrigin", "").orEmpty().isNotEmpty())
            .put("status", p.getString("fcmAcknowledgement", "Enable phone alerts, then enroll the relay in Galaxy → Sentry.")).toString()
    }
    fun enable(ctx: Context, on: Boolean): Boolean {
        if (on && !permitted(ctx)) return false
        val p = prefs(ctx)
        if (on && !p.contains("sentryLastSeenNs")) p.edit().putLong("sentryLastSeenNs", System.currentTimeMillis() * 1000000).apply()
        p.edit().putBoolean("sentryPushEnabled", on).putBoolean("sentryEnrollRequested", on).apply()
        if (on && !FirebaseRegistration.available) p.edit().putString("fcmAcknowledgement", "Polling only: Firebase is not configured in this build").apply()
        if (on) { FirebaseRegistration.start(ctx); SentryPollJob.schedule(ctx); api?.let { registerIfNeeded(ctx, it) } }
        else {
            FirebaseRegistration.stop(ctx)
            SentryPollJob.cancel(ctx)
            val token = p.getString("fcmToken", "").orEmpty()
            val current = api
            p.edit().remove("fcmRegisteredOrigin").apply()
            Thread { runCatching { current?.request("/api/sentry/notifications", JSONObject().put("action", "appUnsubscribe").put("token", token)) } }.start()
        }
        return true
    }
    fun token(ctx: Context, token: String) {
        prefs(ctx).edit().putString("fcmToken", token).remove("fcmRegisteredOrigin").apply()
        api?.let { registerIfNeeded(ctx, it) }
    }
    fun registerIfNeeded(ctx: Context, current: SentryApi) {
        if (suspended) return
        api = current
        val p = prefs(ctx)
        if (!p.getBoolean("sentryPushEnabled", false)) {
            val registrationKey = "fcmSubscription:" + current.origin
            Thread { synchronized(this) { runCatching {
                if (!p.getBoolean("sentryPushEnabled", false)) {
                    val previous = p.getString(registrationKey, "").orEmpty()
                    if (previous.isNotEmpty()) {
                        current.request("/api/sentry/notifications", JSONObject().put("action", "appUnsubscribe").put("token", previous))
                        p.edit().remove(registrationKey).apply()
                    }
                }
            } } }.start()
            return
        }
        val token = p.getString("fcmToken", "").orEmpty()
        if (!FirebaseRegistration.available || token.isEmpty()) return
        Thread { synchronized(this) { runCatching {
            if (!p.getBoolean("sentryPushEnabled", false) || p.getString("fcmToken", "") != token) return@runCatching
            val registrationKey = "fcmSubscription:" + current.origin
            val previous = p.getString(registrationKey, "").orEmpty()
            if (previous.isNotEmpty() && previous != token) {
                current.request("/api/sentry/notifications", JSONObject().put("action", "appUnsubscribe").put("token", previous))
                p.edit().remove(registrationKey).apply()
            }
            var result = current.request("/api/sentry/notifications", JSONObject().put("action", "appSubscribe").put("token", token)
                .put("appId", BuildConfig.APPLICATION_ID).put("label", Build.MODEL.take(64)))
            p.edit().putString(registrationKey, token).apply()
            result = enrollIfRequested(ctx, current, result)
            if (p.getBoolean("sentryPushEnabled", false) && p.getString("fcmToken", "") == token) {
                p.edit().putString("fcmRegisteredOrigin", current.origin).putString("fcmAcknowledgement", relayStatus(result)).apply()
            } else {
                current.request("/api/sentry/notifications", JSONObject().put("action", "appUnsubscribe").put("token", token))
                p.edit().remove(registrationKey).apply()
            }
        }.onFailure { p.edit().putString("fcmAcknowledgement", "Registration failed; reconnect to retry").apply() } } }.start()
    }

    internal fun enrollIfRequested(ctx: Context, current: SentryApi, snapshot: JSONObject): JSONObject {
        val p = prefs(ctx)
        var result = snapshot
        if (p.getBoolean("sentryEnrollRequested", false) && p.getBoolean("sentryPushEnabled", false)) {
            val enrollment = result.optJSONObject("relayEnrollment")
            if (enrollment?.optBoolean("automaticConfigured") == true || enrollment?.optString("state") in listOf("enrolling", "enrolled", "rotating", "confirming")) {
                result = current.request("/api/sentry/notifications", JSONObject().put("action", "relayEnroll").put("confirmed", true))
                p.edit().putBoolean("sentryEnrollRequested", false).apply()
            } else if (enrollment == null) p.edit().putBoolean("sentryEnrollRequested", false).apply()
        }
        return result
    }
    internal fun relayStatus(result: JSONObject): String {
        return when (result.optJSONObject("relayEnrollment")?.optString("state")) {
            "enrolling", "rotating", "confirming" -> "Phone registered; waiting for relay enrollment in Galaxy → Sentry"
            "unenrolled", "revoked" -> if (result.optJSONObject("relayEnrollment")?.optBoolean("automaticConfigured") == true) "Enable alerts to enroll this comma automatically" else "Phone registered; hosted alerts are not configured in this release yet"
            "revoking" -> "Relay revocation pending; push alerts paused"
            "actionRequired" -> "Relay authorization needs attention in Galaxy → Sentry"
            else -> if (result.optBoolean("appPushAvailable")) "Registered; send a test from Galaxy → Sentry" else "Polling; relay not configured"
        }
    }
    private fun decode(bytes: ByteArray, sample: Int): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    @Synchronized fun receive(ctx: Context, data: Map<String, String>, fromPush: Boolean = true, demo: Boolean = false): Boolean {
        val p = prefs(ctx)
        if (!demo && suspended) return false
        if (!demo && !p.getBoolean("sentryPushEnabled", false) || data["type"] != "sentry" || data["kind"] !in setOf("warning", "alarm", "test") || !permitted(ctx)) return false
        val id = data["eventId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return false
        val seen = runCatching { JSONArray(p.getString("sentrySeenIds", "[]")) }.getOrDefault(JSONArray())
        val dedupe = SentryDedupe((0 until seen.length()).map { seen.getString(it) })
        if (!demo && !dedupe.accept(id)) return false
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Notifications.SENTRY_CHANNEL, "Sentry alerts", NotificationManager.IMPORTANCE_HIGH))
        val notificationId = Notifications.SENTRY_BASE + (id.hashCode() and 0x3FF)
        val intent = Intent(ctx, MainActivity::class.java).putExtra("openRoute", "/cameras/events").putExtra("demo", demo)
        val open = PendingIntent.getActivity(ctx, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (data["kind"]) { "alarm" -> "Sentry alarm"; "test" -> "Galaxy test alert"; else -> "Sentry: motion detected" }
        val builder = Notification.Builder(ctx, Notifications.SENTRY_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(data["label"].orEmpty().take(64)).setAutoCancel(true).setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (data["kind"] == "alarm") builder.setCategory(Notification.CATEGORY_ALARM)
        if (demo) manager.notify(notificationId, builder.build())
        else {
            p.edit().putString("sentrySeenIds", JSONArray(dedupe.ids()).toString())
                .putLong("sentryLastSeenNs", maxOf(p.getLong("sentryLastSeenNs", 0), (data["wallTimeNs"]?.toLongOrNull() ?: 0).coerceIn(0, System.currentTimeMillis() * 1000000)))
                .apply { if (fromPush) putLong("sentryLastPushAt", System.currentTimeMillis()) }.commit()
            val test = data["kind"] == "test"
            Thread {
                val images = java.util.concurrent.FutureTask {
                    val current = api ?: SentryApi.best(ctx) ?: return@FutureTask null
                    val wide = if (test) null else runCatching { decode(current.bytes("/api/sentry/image/$id/wide"), 1) }.getOrNull()
                    val cabin = runCatching {
                        decode(if (test) current.bytes("/api/cameras/snapshot", JSONObject().put("camera", "cabin"), 30000)
                               else current.bytes("/api/sentry/image/$id/cabin"), 4)
                    }.getOrNull()?.let { bitmap ->
                        val balanced = runCatching { current.request("/api/cameras/white-balance", JSONObject()).optBoolean("enabled") }.getOrDefault(false)
                        if (balanced) CabinWhiteBalance.apply(bitmap) else bitmap
                    }
                    wide to cabin
                }
                Thread(images).apply { isDaemon = true; start() }
                fun post(result: Pair<Bitmap?, Bitmap?>?) {
                    result?.first?.let { builder.setStyle(Notification.BigPictureStyle().bigPicture(it)) }
                    result?.second?.let { builder.setLargeIcon(it) }
                    if (!suspended) manager.notify(notificationId, builder.build())
                }
                val unit = java.util.concurrent.TimeUnit.SECONDS
                try { post(images.get(if (test) 12L else 5L, unit)); return@Thread } catch (_: Exception) { post(null) }
                try { images.get(if (test) 23L else 3L, unit)?.takeIf { it.first != null || it.second != null }?.let(::post) }
                catch (_: Exception) {} finally { images.cancel(true) }
            }.start()
        }
        return true
    }
}
