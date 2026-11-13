package com.starpilot.galaxy

/** Small in-memory debug snapshot; navigation text is never written to preferences/logs. */
internal class NavigationDiagnostics(private val clock: () -> Long) {
    private var connected = false
    private var state = "Waiting for a navigation notification"
    private var source = ""
    private var notificationSource = ""
    private var notificationResult = ""
    private var raw: NavigationNotificationText? = null
    private var guidance: NavigationGuidance? = null
    private var detectedAt = -1L
    private var observedAt = -1L
    private var deliveredAt = -1L
    private var commaEnabled: Boolean? = null
    private var transport = ""
    private var delivery = "No navigation update sent"
    private var iconOrigin = ""
    private var iconHash: String? = null
    private var iconResult = ""
    private var totalsUnsupported = false

    @Synchronized fun listener(value: Boolean) { connected = value }
    @Synchronized fun detected(provider: String, text: NavigationNotificationText, parsed: NavigationGuidance?, stamp: Long, selected: Boolean = true) {
        notificationSource = provider; raw = text; detectedAt = clock()
        notificationResult = if (selected) "Selected" else "Ignored: incomplete, older or lower-priority notification"
        if (!selected) return
        source = provider; guidance = parsed; observedAt = stamp
        state = if (parsed == null) "Rerouting or incomplete notification; previous turn cleared" else "Navigation detected"
    }
    @Synchronized fun icon(origin: String, hash: String?, result: String) { iconOrigin = origin; iconHash = hash; iconResult = result }
    @Synchronized fun cleared(reason: String) { guidance = null; state = reason }
    @Synchronized fun delivered(via: String, accepted: Boolean, forwardingEnabled: Boolean? = null) {
        transport = via; deliveredAt = clock(); commaEnabled = forwardingEnabled
        delivery = if (accepted) "Accepted by StarPilot" else "StarPilot ignored an older/session update"
    }
    @Synchronized fun failed(reason: String) { delivery = "Delivery failed: " + reason.take(180) }
    @Synchronized fun tripTotalsUnsupported() { totalsUnsupported = true }
    @Synchronized fun details(enabled: Boolean, access: Boolean): String {
        fun age(stamp: Long) = if (stamp < 0) "never" else "${((clock() - stamp).coerceAtLeast(0) / 1000)} s ago"
        val current = when {
            !enabled -> "Off"
            !access -> "Notification access required"
            !connected -> "Listener disconnected"
            guidance != null && clock() - observedAt > 90_000 -> "Observation expired; no current turn"
            else -> state
        }
        return buildString {
            append("Navigation forwarding\nEnabled: $enabled\nNotification access: $access\nListener connected: $connected\nState: $current\n")
            append("Source: ${source.ifEmpty { "none" }}\nLast detection: ${age(detectedAt)}\nObservation: ${age(observedAt)}\n")
            guidance?.let {
                append("Last parsed guidance\nInstruction: ${it.instruction}\nManeuver: ${it.maneuverType.ifEmpty { "unknown" }} ${it.modifier}\n")
                append("Distance: ${it.distanceMeters?.let { meters -> "$meters m" } ?: "unknown"}\n")
                append("Road: ${it.roadName ?: "unknown"}\nExit: ${it.exitNumber ?: "unknown"}\n")
                val meters = it.remainingMeters
                val seconds = it.remainingSeconds
                append("Trip remaining: ${if (meters == null || seconds == null) "unknown" else "%.1f km · %d min".format(java.util.Locale.US, meters / 1000, seconds / 60)}\n")
            }
            if (totalsUnsupported) append("Trip totals not sent: this StarPilot version does not accept them\n")
            append("Last delivery: ${age(deliveredAt)}\nTransport: ${transport.ifEmpty { "none" }}\n$delivery\n")
            append("Comma phone-navigation setting: ${commaEnabled?.let { if (it) "On" else "Off — enable it in Galaxy Navigation Setup" } ?: "unknown"}\n")
            append("Active Mapbox navigation takes priority. Turns the comma's map confirms can slow the car and assist a signalled turn.\n")
            if (enabled) raw?.let {
                append("\nLast notification ($notificationSource) · $notificationResult\nTitle: ${it.title}\nText: ${it.text}\n")
                if (it.bigText.isNotBlank()) append("Expanded: ${it.bigText.take(500)}\n")
                if (it.subText.isNotBlank()) append("Sub text: ${it.subText.take(500)}\n")
                if (it.lines.isNotEmpty()) append("Lines: ${it.lines.take(6).joinToString(" | ").take(1000)}\n")
                it.viewFields.forEach { (name, value) -> append("$name: ${value.take(500)}\n") }
                append("Arrow icon: ${iconOrigin.ifEmpty { "none" }} ${iconHash?.take(16) ?: "no shape"} · $iconResult\n")
            }
        }
    }
}
