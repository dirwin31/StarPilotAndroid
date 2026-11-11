package com.starpilot.galaxy

/** One message waiting for the comma. Content stays in memory only until sent or expired. */
internal data class PhoneNotification(
    val key: String, val app: String, val appName: String, val sender: String, val text: String,
    val category: String, val postedAt: Long,
)

/** Pure field extraction; Android types stay in [PhoneNotificationForwarder] so this runs on the JVM. */
internal object PhoneNotificationExtractor {
    /** A MessagingStyle message; a null sender is the user's own message. */
    data class Message(val sender: String?, val text: String, val time: Long = 0)
    data class Fields(val sender: String, val text: String, val category: String, val identity: String)
    val SKIPPED_CATEGORIES = setOf("transport", "progress", "service", "sys", "status", "navigation", "stopwatch", "location_sharing", "workout")
    private val control = Regex("""[\p{Cc}‎‏‪-‮⁦-⁩]+""")
    private val space = Regex("""\s+""")

    fun clean(value: CharSequence?, limit: Int): String {
        val text = value?.toString().orEmpty().replace(control, " ").replace(space, " ").trim()
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end).trimEnd()
    }

    /** The comma shows only "message" by default, so MessagingStyle always counts as a message. */
    fun category(category: String?, messaging: Boolean) = if (messaging) "message" else when (category) {
        "msg" -> "message"
        "call", "missed_call" -> "call"
        "email" -> "email"
        "social" -> "social"
        else -> "other"
    }

    /** MessagingStyle wins: only the newest message, skipped when it is the user's own reply. */
    fun extract(title: CharSequence?, text: CharSequence?, bigText: CharSequence?, conversationTitle: CharSequence?,
                messages: List<Message>, category: String?, selfName: CharSequence? = null): Fields? {
        val newest = messages.lastOrNull()
        val sender: String
        val body: String
        if (newest != null) {
            val name = clean(newest.sender ?: return null, 120)
            val self = clean(selfName, 120)
            if (self.isNotEmpty() && name.equals(self, ignoreCase = true)) return null
            val group = clean(conversationTitle, 120)
            sender = clean(when {
                name.isEmpty() -> group
                group.isEmpty() || group.equals(name, ignoreCase = true) -> name
                else -> "$name · $group"
            }, 120)
            body = clean(newest.text, 500).ifEmpty { clean(text, 500) }
        } else {
            sender = clean(title, 120)
            body = clean(bigText, 500).ifEmpty { clean(text, 500) }
        }
        if (sender.isEmpty() && body.isEmpty()) return null
        return Fields(sender, body, this.category(category, newest != null), sender + "\u0000" + body + "\u0000" + (newest?.time ?: 0L))
    }
}

/** Apps re-post unchanged notifications; only a hash of the content is kept, never the text. */
internal class PhoneNotificationDedupe(private val windowMs: Long = 60_000, private val capacity: Int = 64) {
    private val seen = LinkedHashMap<String, Pair<Int, Long>>()
    @Synchronized fun fresh(key: String, identity: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value.second > windowMs }
        val hash = identity.hashCode()
        val repeated = seen[key]?.first == hash
        seen.remove(key)
        seen[key] = hash to now
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return !repeated
    }
}

/** Newest [capacity] items, each dropped once older than [maxAgeMs]. */
internal class PhoneNotificationQueue(private val capacity: Int = 8, private val maxAgeMs: Long = 8_000) {
    private val items = ArrayDeque<PhoneNotification>()
    val size: Int
        @Synchronized get() = items.size
    /** Returns how many older items were dropped to make room. */
    @Synchronized fun offer(item: PhoneNotification): Int {
        items.addLast(item)
        var dropped = 0
        while (items.size > capacity) { items.removeFirst(); dropped++ }
        return dropped
    }
    @Synchronized fun expire(now: Long): Int {
        val before = items.size
        items.removeAll { now - it.postedAt > maxAgeMs }
        return before - items.size
    }
    @Synchronized fun peek(): PhoneNotification? = items.firstOrNull()
    @Synchronized fun remove(item: PhoneNotification) { items.remove(item) }
    @Synchronized fun clear() = items.clear()
}

/** Content-free status for Notification details: results and counts only. */
internal class PhoneNotificationDiagnostics(private val clock: () -> Long) {
    private var forwardedAt = -1L
    private var result = "Nothing forwarded yet"
    private val counts = linkedMapOf<String, Int>()

    @Synchronized fun count(what: String, amount: Int = 1) { if (amount > 0) counts[what] = (counts[what] ?: 0) + amount }
    @Synchronized fun delivered(accepted: Boolean, enabled: Boolean) {
        forwardedAt = clock()
        result = when { !enabled -> "Comma switch off"; accepted -> "Accepted"; else -> "Filtered on comma" }
        counts[result.lowercase()] = (counts[result.lowercase()] ?: 0) + 1
    }
    @Synchronized fun unreachable() { result = "StarPilot unreachable; retrying in about 60 s"; counts["unreachable"] = (counts["unreachable"] ?: 0) + 1 }
    @Synchronized fun rejected(status: Int) { result = "Rejected by StarPilot (HTTP $status)"; counts["rejected"] = (counts["rejected"] ?: 0) + 1 }
    @Synchronized fun tooOld(status: Int) { result = "Comma too old (HTTP $status); update StarPilot. Stopped until the listener restarts" }
    @Synchronized fun details(enabled: Boolean, access: Boolean): String = buildString {
        val age = if (forwardedAt < 0) "never" else "${(clock() - forwardedAt).coerceAtLeast(0) / 1000} s ago"
        append("Phone notifications\nEnabled: $enabled\nNotification access: $access\n")
        append("Last forwarded: $age\nLast result: $result\n")
        append("Counts: ${if (counts.isEmpty()) "none" else counts.entries.joinToString(" · ") { "${it.key} ${it.value}" }}\n")
        append("Message content is never stored or logged. Choose apps in Galaxy → StarPort → Phone notifications.\n")
    }
}
