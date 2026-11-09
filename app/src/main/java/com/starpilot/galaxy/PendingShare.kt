package com.starpilot.galaxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The last shared place waiting for comma (latest wins, kept 12 hours). Every send to comma runs here, one at a time. */
object PendingShare {
    private const val TEXT = "pendingShare"
    private const val TIME = "pendingShareTime"
    private const val TTL = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "shared_places"
    private val executor = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean(false)
    private val sharing = AtomicInteger()
    private fun prefs(context: Context) = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    private fun load(context: Context): Pair<String, Long>? = synchronized(this) {
        val prefs = prefs(context)
        val text = prefs.getString(TEXT, null) ?: return null
        val time = prefs.getLong(TIME, 0)
        if (System.currentTimeMillis() - time !in 0..TTL) { clear(context, time); return null }
        text to time
    }
    private fun clear(context: Context, time: Long) = synchronized(this) {
        val prefs = prefs(context)
        if (prefs.getLong(TIME, -1) != time) return
        prefs.edit().remove(TEXT).remove(TIME).apply()
        PendingShareJob.unwatch(context)
    }

    /** Saves [text] first so it survives a failed send, then sends it. [show] returns false when nobody is looking, which posts a notification instead. */
    fun share(context: Context, text: String, show: (CommaNavigation.Outcome?, String) -> Boolean) {
        val app = context.applicationContext
        val time = synchronized(this) {
            val now = maxOf(System.currentTimeMillis(), prefs(app).getLong(TIME, 0) + 1)
            prefs(app).edit().putString(TEXT, text).putLong(TIME, now).commit()
            now
        }
        runCatching { PendingShareJob.watch(app) }
        sharing.incrementAndGet()
        executor.execute { try { attempt(app, text, time, false, show) } finally { sharing.decrementAndGet() } }
    }

    fun waiting(context: Context) = load(context) != null

    /** Sends the waiting place, if any, now that comma may be reachable. */
    fun deliver(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        if (load(app) == null || !queued.compareAndSet(false, true)) return finished()
        executor.execute {
            queued.set(false)
            try {
                // A share still in the queue sends the newest place itself.
                if (sharing.get() == 0) load(app)?.let { (text, time) -> attempt(app, text, time, true) { _, _ -> false } }
            } finally { finished() }
        }
    }

    /** A retry that still cannot reach comma stays silent; a new share nobody watched says it was saved. */
    private fun attempt(context: Context, text: String, time: Long, retry: Boolean, show: (CommaNavigation.Outcome?, String) -> Boolean) {
        val outcome = try { CommaSender(context).share(text) } catch (error: Exception) {
            val reason = error.message ?: CommaSender.UNREACHABLE
            if (!show(null, reason) && !retry) notify(context, null, text)
            return
        }
        clear(context, time)
        if (!show(outcome, "")) notify(context, outcome, text)
    }

    private fun notify(context: Context, outcome: CommaNavigation.Outcome?, text: String) {
        if (!SentryPush.permitted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.share_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (outcome == CommaNavigation.Outcome.NeedsSearch) open.putExtra("share", text)
        val title = when (outcome) {
            is CommaNavigation.Outcome.Started -> context.getString(R.string.destination_sent, outcome.name)
            is CommaNavigation.Outcome.Refused -> context.getString(R.string.share_refused, outcome.message)
            CommaNavigation.Outcome.NeedsSearch -> context.getString(R.string.share_search)
            null -> context.getString(R.string.share_saved)
        }
        manager.notify(Notifications.SHARED_PLACE, Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title).setStyle(Notification.BigTextStyle().bigText(title)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, Notifications.SHARED_PLACE, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
    }
}

/** Retries the waiting place every 15 minutes and whenever the phone joins a Wi-Fi network, which may be comma's hotspot. */
class PendingShareJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        if (!PendingShare.waiting(applicationContext)) { unwatch(applicationContext); return false }
        PendingShare.deliver(applicationContext) { handler.post { jobFinished(params, false) } }
        return true
    }
    override fun onStopJob(params: JobParameters) = false
    companion object {
        private const val PERIODIC = 9
        private const val NOW = 10
        private fun wifi(context: Context) = PendingIntent.getBroadcast(context, 0, Intent(context, PendingShareReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        fun watch(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(PERIODIC, ComponentName(context, PendingShareJob::class.java))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
            listen(context)
        }
        /** Network callbacks end at reboot; BootReceiver registers again. */
        fun listen(context: Context) {
            Networks.manager(context).registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), wifi(context))
        }
        fun unwatch(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(PERIODIC); scheduler.cancel(NOW)
            runCatching { Networks.manager(context).unregisterNetworkCallback(wifi(context)) }
        }
        fun now(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(NOW, ComponentName(context, PendingShareJob::class.java))
                .setMinimumLatency(1000).build())
        }
    }
}

class PendingShareReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { runCatching { PendingShareJob.now(context) } }
}
