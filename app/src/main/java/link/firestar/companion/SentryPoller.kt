package link.firestar.companion

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object SentryPoller {
    private val busy = AtomicBoolean()
    fun poll(ctx: Context, current: SentryApi? = null) {
        val p = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)
        if (SentryPush.suspended || !p.getBoolean("sentryPushEnabled", false) || !busy.compareAndSet(false, true)) return
        try {
            val api = current ?: SentryApi.best(ctx) ?: return
            val events = api.request("/api/sentry/events").optJSONArray("events") ?: return
            val since = p.getLong("sentryLastSeenNs", System.currentTimeMillis() * 1000000)
            (0 until events.length()).map { events.getJSONObject(it) }.sortedBy { it.optLong("systemTimeMs") }.forEach { event ->
                val ns = event.optLong("wallTimeNs", event.optLong("systemTimeMs") * 1000000)
                if (ns > since) SentryPush.receive(ctx, mapOf("type" to "sentry", "eventId" to event.optString("eventId"),
                    "kind" to event.optString("kind"), "wallTimeNs" to ns.toString(), "label" to "Galaxy"), fromPush = false)
            }
            p.edit().putLong("sentryLastPollAt", System.currentTimeMillis()).apply()
        } finally { busy.set(false) }
    }
}
