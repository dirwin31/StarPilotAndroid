package com.starpilot.galaxy

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            .put("registered", p.getString("fcmRegisteredOrigin", "").orEmpty().isNotEmpty()).toString()
    }
    fun enable(ctx: Context, on: Boolean): Boolean {
        if (on && !permitted(ctx)) return false
        val p = prefs(ctx)
        if (on && !p.contains("sentryLastSeenNs")) p.edit().putLong("sentryLastSeenNs", System.currentTimeMillis() * 1000000).apply()
        p.edit().putBoolean("sentryPushEnabled", on).apply()
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
        if (!p.getBoolean("sentryPushEnabled", false)) return
        val token = p.getString("fcmToken", "").orEmpty()
        if (!FirebaseRegistration.available || token.isEmpty()) return
        Thread { runCatching {
            val result = current.request("/api/sentry/notifications", JSONObject().put("action", "appSubscribe").put("token", token)
                .put("appId", BuildConfig.APPLICATION_ID).put("label", Build.MODEL.take(64)))
            if (p.getBoolean("sentryPushEnabled", false) && p.getString("fcmToken", "") == token) {
                p.edit().putString("fcmRegisteredOrigin", current.origin).putString("fcmAcknowledgement", if (result.optBoolean("appPushAvailable")) "Registered" else "Polling; relay not configured").apply()
            }
        }.onFailure { p.edit().putString("fcmAcknowledgement", "Registration failed").apply() } }.start()
    }
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
        if (data["kind"] == "alarm") builder.setCategory(Notification.CATEGORY_ALARM)
        manager.notify(notificationId, builder.build())
        if (!demo) {
            p.edit().putString("sentrySeenIds", JSONArray(dedupe.ids()).toString())
                .putLong("sentryLastSeenNs", maxOf(p.getLong("sentryLastSeenNs", 0), (data["wallTimeNs"]?.toLongOrNull() ?: 0).coerceIn(0, System.currentTimeMillis() * 1000000)))
                .apply { if (fromPush) putLong("sentryLastPushAt", System.currentTimeMillis()) }.commit()
            Thread {
                val image = java.util.concurrent.FutureTask {
                    val current = api ?: SentryApi.best(ctx)
                    current?.bytes("/api/sentry/image/$id/wide")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                Thread(image).apply { isDaemon = true; start() }
                try {
                    val bitmap = image.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (bitmap != null && !suspended) manager.notify(notificationId, builder.setStyle(Notification.BigPictureStyle().bigPicture(bitmap)).build())
                } catch (_: Exception) {} finally { image.cancel(true) }
            }.start()
        }
        return true
    }
}
