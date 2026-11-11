package com.starpilot.galaxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Forwards newly posted message notifications to the comma's StarPort toast.
 * Filtering by app happens on the comma; sender and text are never stored, logged or written to prefs.
 */
internal class PhoneNotificationForwarder(private val service: NotificationListenerService) {
    companion object {
        const val PATH = "/api/companion/notification"
        const val ENABLED = "notificationsEnabled"
        const val DISCLOSURE = "notificationsDisclosureAccepted"
        private const val BACKOFF_MS = 60_000L
        internal val diagnostics = PhoneNotificationDiagnostics { SystemClock.elapsedRealtime() }
        const val TEST_EXTRA = "com.starpilot.galaxy.NOTIFICATION_TEST"
        const val TEST_TITLE = "StarPilot test notification"
        private const val TEST_CHANNEL = "notification-test"
        private val testId = AtomicInteger(0)
        fun enabled(context: Context) = NavigationNotificationListener.preferences(context).getBoolean(ENABLED, false)

        /** Posts a real notification that the listener forwards like any other, so it checks the whole path. */
        fun postTest(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            manager.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "Notification sharing test", NotificationManager.IMPORTANCE_DEFAULT))
            // A new id each tap, so the repeat filter never drops a second test.
            val id = Notifications.NOTIFICATION_TEST_BASE + testId.getAndIncrement() % 100
            manager.notify(id, Notification.Builder(context, TEST_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(TEST_TITLE).setContentText("If sharing works, this shows on your comma and car screen.")
                .addExtras(Bundle().apply { putBoolean(TEST_EXTRA, true) }).setAutoCancel(true).setTimeoutAfter(15_000).build())
            return true
        }
    }
    private val queue = PhoneNotificationQueue()
    private val dedupe = PhoneNotificationDedupe()
    private val labels = HashMap<String, String>()
    @Volatile private var backoffUntil = 0L
    @Volatile private var unsupported = false

    fun reachable() { backoffUntil = 0L }
    fun clear() = queue.clear()

    /** Main thread. Returns true when an item was queued and the worker should drain. */
    fun posted(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?): Boolean {
        if (unsupported || !enabled(service)) return false
        val notification = sbn.notification ?: return false
        val test = sbn.packageName == service.packageName && notification.extras?.getBoolean(TEST_EXTRA) == true
        if ((sbn.packageName == service.packageName && !test) || (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || sbn.isOngoing ||
            (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 || (notification.category ?: "") in PhoneNotificationExtractor.SKIPPED_CATEGORIES) return false
        val extras = notification.extras ?: return false
        val media = runCatching {
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle")
        }.getOrDefault(false)
        if (media) return false
        rankingSkip(sbn.key, rankingMap)?.let { diagnostics.count(it); return false }
        val fields = runCatching { extract(extras, notification.category) }.getOrNull()
        if (fields == null) { diagnostics.count("ignored: empty or own reply"); return false }
        val now = SystemClock.elapsedRealtime()
        if (!dedupe.fresh(sbn.key, fields.identity, now)) { diagnostics.count("ignored: repeated"); return false }
        val age = (System.currentTimeMillis() - sbn.postTime).coerceIn(0L, 60_000L)
        val appName = labels.getOrPut(sbn.packageName) { label(sbn.packageName, extras) }
        diagnostics.count("queued")
        diagnostics.count("dropped: queue full", queue.offer(PhoneNotification(sbn.key, sbn.packageName.take(128), appName,
            fields.sender, fields.text, fields.category, now - age)))
        return true
    }

    private fun rankingSkip(key: String, map: NotificationListenerService.RankingMap?): String? {
        val ranking = NotificationListenerService.Ranking()
        val rankings = map ?: runCatching { service.currentRanking }.getOrNull() ?: return null
        if (!runCatching { rankings.getRanking(key, ranking) }.getOrDefault(false)) return null
        return when {
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT -> "ignored: low importance"
            Build.VERSION.SDK_INT >= 29 && ranking.isSuspended -> "ignored: app suspended"
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun extract(extras: Bundle, category: String?): PhoneNotificationExtractor.Fields? {
        fun person(bundle: Bundle, key: String) = if (Build.VERSION.SDK_INT >= 28) bundle.getParcelable<Person>(key)?.name else null
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().filterIsInstance<Bundle>().map {
            PhoneNotificationExtractor.Message((person(it, "sender_person") ?: it.getCharSequence("sender"))?.toString(),
                it.getCharSequence("text")?.toString().orEmpty(), it.getLong("time"))
        }
        val self = person(extras, Notification.EXTRA_MESSAGING_PERSON) ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
        return PhoneNotificationExtractor.extract(extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT), extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            messages, category, self)
    }

    /** Package visibility may hide other apps; the comma then shows the package name. */
    @Suppress("DEPRECATION")
    private fun label(packageName: String, extras: Bundle): String = runCatching {
        val info = runCatching { extras.getParcelable<ApplicationInfo>("android.appInfo") }.getOrNull()
            ?: service.packageManager.getApplicationInfo(packageName, 0)
        PhoneNotificationExtractor.clean(info.loadLabel(service.packageManager), 80)
    }.getOrDefault("")

    /** Worker thread only, sharing the listener's single [LocalCompanionSender]. */
    fun drain(sender: LocalCompanionSender) {
        val now = SystemClock.elapsedRealtime()
        // The comma accepts but no longer shows anything 8 s or older.
        diagnostics.count("dropped: older than 8 s", queue.expire(now))
        if (queue.peek() == null) return
        if (unsupported || !enabled(service)) { queue.clear(); return }
        if (now < backoffUntil) return
        while (true) {
            val item = queue.peek() ?: return
            var response: JSONObject? = null
            try {
                sender.post(PATH, deadlineSeconds = 5, onResponse = { _, json -> response = json }) {
                    val age = (SystemClock.elapsedRealtime() - item.postedAt).coerceIn(0L, 60_000L)
                    Unit to JSONObject().put("source", "android").put("app", item.app).put("appName", item.appName)
                        .put("sender", item.sender).put("text", item.text).put("category", item.category).put("ageMs", age)
                        .toString().toByteArray()
                }
                queue.remove(item)
                backoffUntil = 0L
                val reply = response
                diagnostics.delivered(reply?.optBoolean("accepted", false) == true, reply?.optBoolean("enabled", true) != false)
            } catch (failure: CompanionHttpException) {
                queue.remove(item)
                if (failure.status == 404 || failure.status == 405) {
                    unsupported = true; queue.clear(); diagnostics.tooOld(failure.status); return
                }
                diagnostics.rejected(failure.status)
            } catch (_: Exception) {
                backoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
                diagnostics.unreachable()
                return
            }
        }
    }
}
