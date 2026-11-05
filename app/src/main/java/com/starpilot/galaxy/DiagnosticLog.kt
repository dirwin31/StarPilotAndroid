package com.starpilot.galaxy

import java.time.Instant
import org.json.JSONObject

/**
 * In-memory ring of recent connection events. Bounded at the source so a report never
 * approaches the relay's upload limit. Holds no tokens; callers must not record secrets.
 */
object DiagnosticLog {
    const val MAX_EVENTS = 300
    const val MAX_EVENT_CHARS = 500
    private val events = ArrayDeque<String>()
    private var last = ""
    @Volatile var activeConnection = "Not connected"
    @Volatile var lastFailure = "None recorded"
    @Volatile var connectionStatus = "Not connected"
    @Volatile var musicStatus = "No music setup attempted this session"
    @Volatile var bluetoothPhase = "No Bluetooth attempt this session"
    private val routes = linkedMapOf<String, String>()
    private var connectionEpoch = 0
    private var software = emptyMap<String, String>()
    private var softwareEpoch = -1

    @Synchronized fun beginConnection(): Int {
        activeConnection = "Not connected"
        connectionStatus = "Selecting connection"
        return ++connectionEpoch
    }
    @Synchronized fun epoch(): Int = connectionEpoch
    @Synchronized fun routeStatus(route: String, state: String, detail: String) {
        routes[route] = "$state: ${redact(detail).take(200)}"
        if (state == "TRYING" || state == "FAILED" && activeConnection == "Not connected")
            connectionStatus = "$route $state: ${redact(detail).take(200)}"
        record("$route $state: $detail")
        if (state == "FAILED") lastFailure = redact("$route: $detail").take(MAX_EVENT_CHARS)
    }

    /** Keep only documented software identity fields, never the full device response. */
    @Synchronized fun observeSoftware(status: JSONObject, source: String, epoch: Int, now: Instant = Instant.now()) {
        if (epoch != connectionEpoch || status.optInt("schemaVersion") != 1) return
        val installed = status.optJSONObject("installed") ?: return
        fun field(name: String): String? = (installed.opt(name) as? String)?.trim()
            ?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._+() /-]+")) }
        val version = field("displayVersion") ?: field("version") ?: "Unknown"
        val branch = field("branch") ?: "Unknown"
        val commit = (installed.opt("commit") as? String)?.takeIf { it.matches(Regex("[a-fA-F0-9]{7,40}")) } ?: "Unknown"
        if (version == "Unknown" && branch == "Unknown" && commit == "Unknown") return
        software = linkedMapOf("Comma version" to version, "Comma branch" to branch, "Comma commit" to commit,
            "Comma software observed (UTC)" to now.toString(), "Comma software source" to source)
        softwareEpoch = epoch
        record("Comma software identity received successfully ($source)", now)
    }

    /** Defence in depth: callers log connection outcomes, never request bodies or credentials. */
    fun redact(value: String, redactAddresses: Boolean = true): String = value
        .replace(Regex("(?i)\\bBearer\\s+\\S+"), "Bearer [redacted]")
        .replace(Regex("(?i)([\\\"]?(?:access_token|refresh_token|password|token|secret|cookie|authorization|app[_ -]?key)[\\\"]?\\s*[:=]\\s*)[\\\"]?[^\\s,;\\\"}]+"), "$1[redacted]")
        .replace(Regex("(?i)\\b(?:https?|starpilot-remote|starpilot-discord)://\\S+"), "[URL redacted]")
        .let { if (redactAddresses) it.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"), "[IP redacted]") else it }
        .replace(Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b"), "[address redacted]")
        .replace(Regex("\\b[A-Za-z0-9_-]{43,}\\b"), "[credential redacted]")

    @Synchronized fun record(message: String, now: Instant = Instant.now()) {
        val clean = redact(message).replace(Regex("[\\r\\n\\u0000-\\u001f]+"), " ").trim().take(MAX_EVENT_CHARS)
        if (clean.isEmpty() || clean == last) return
        last = clean
        events.addLast("$now $clean")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized fun clear() {
        events.clear(); last = ""; routes.clear(); software = emptyMap(); softwareEpoch = -1
        activeConnection = "Not connected"; connectionStatus = "Not connected"; lastFailure = "None recorded"
        musicStatus = "No music setup attempted this session"
        bluetoothPhase = "No Bluetooth attempt this session"
        connectionEpoch++
    }

    fun failure(message: String) {
        lastFailure = redact(message).take(MAX_EVENT_CHARS)
        record("Failure: $lastFailure")
    }

    /** Capture once for submission. The submitted report and every retry retain this exact snapshot. */
    @Synchronized fun snapshot(header: Map<String, String>, now: Instant = Instant.now()): String {
        val lines = mutableListOf("StarPilot Android report submission", "Captured (UTC): $now")
        val metadata = linkedMapOf("Comma version" to "Unknown", "Comma branch" to "Unknown", "Comma commit" to "Unknown")
        metadata.putAll(header)
        metadata.putAll(software)
        metadata["Comma software freshness"] = when {
            software.isEmpty() -> "Not available in this app session"
            softwareEpoch == connectionEpoch -> "Observed during this connection attempt; see observation time"
            else -> "Last observed on an earlier connection; may be a different comma"
        }
        metadata.forEach { (key, value) -> lines.add("$key: ${redact(value, key !in setOf("App", "App version", "Android build", "Comma version", "Comma branch")).replace(Regex("[\\r\\n]+"), " ").take(200)}") }
        lines.add("Route results this session:")
        routes.forEach { (route, result) -> lines.add("$route: $result") }
        lines.add("Capture policy: saved snapshot; retries preserve this snapshot.")
        lines.add("Recent events:")
        lines.addAll(events)
        return lines.joinToString("\n")
    }
}
