package com.starpilot.galaxy

/** Phone fix sent to comma's `POST /api/companion/location`; comma uses it only when its own GPS has no fix. */
object PhoneGps {
    const val PATH = "/api/companion/location"
    const val MAX_AGE_MS = 2000L

    data class Fix(
        val latitude: Double, val longitude: Double, val accuracy: Float, val timestamp: Long,
        val altitude: Double? = null, val verticalAccuracy: Float? = null,
        val speed: Float? = null, val speedAccuracy: Float? = null,
        val bearing: Float? = null, val bearingAccuracy: Float? = null,
    )

    /** Null when the fix is too old or has no usable position. Unknown values are left out. */
    fun payload(fix: Fix, ageMs: Long): Map<String, Any>? {
        if (ageMs !in 0..MAX_AGE_MS || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            !fix.accuracy.isFinite() || fix.accuracy <= 0f || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return null
        val values = linkedMapOf<String, Any>("latitude" to fix.latitude, "longitude" to fix.longitude,
            "horizontalAccuracy" to fix.accuracy.toDouble(), "ageMs" to ageMs, "timestamp" to fix.timestamp.coerceAtLeast(0))
        fun put(key: String, value: Number?) { value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }?.let { values[key] = it } }
        fix.altitude?.takeIf { it.isFinite() }?.let { values["altitude"] = it }
        put("verticalAccuracy", fix.verticalAccuracy)
        put("speed", fix.speed)
        put("speedAccuracy", fix.speedAccuracy)
        if (fix.bearingAccuracy != null) { put("bearing", fix.bearing); put("bearingAccuracy", fix.bearingAccuracy) }
        return values
    }
}

/** Decides when the GPS receiver runs and when to ask comma, without GPS, whether a drive started. */
internal class PhoneGpsDuty(
    private val holdMs: Long = 60_000,
    private val checkEveryMs: Long = 10_000,
    private val searchEveryMs: Long = 30_000,
    private val idleSearchEveryMs: Long = 180_000,
    private val searchForMs: Long = 1_800_000,
    private val probeEveryMs: Long = 120_000,
) {
    private var wokenAt: Long? = null
    private var drivingAt: Long? = null
    private var answeredAt: Long? = null
    private var checkedAt: Long? = null
    private var unansweredAt: Long? = null
    private var probedAt: Long? = null
    private fun recent(now: Long, at: Long?, span: Long) = at != null && now - at in 0 until span
    /** StarPilot may be near: the app opened, Bluetooth or Wi-Fi changed, or Maps began navigating. */
    @Synchronized fun wake(now: Long) { wokenAt = now; checkedAt = null; unansweredAt = null }
    /** comma answered a fix or a status check; [driving] is false only while it is parked with the ignition off. */
    @Synchronized fun answered(now: Long, driving: Boolean) {
        answeredAt = now; unansweredAt = null
        if (driving) drivingAt = now else { wokenAt = null; drivingAt = null }
    }
    /** Older StarPilot reports no drive state to a status check, so the receiver probes with a fix now and then. */
    @Synchronized fun statusUnsupported(now: Long) {
        answeredAt = now; unansweredAt = null
        if (!recent(now, probedAt, probeEveryMs)) { probedAt = now; wokenAt = now }
    }
    @Synchronized fun wanted(now: Long) = recent(now, wokenAt, holdMs) || recent(now, drivingAt, holdMs)
    /** A status check could not reach comma. */
    @Synchronized fun unanswered(now: Long) { unansweredAt = now }
    /** True when comma should be asked now. Checks that cannot reach comma back off once it has been away a while. */
    @Synchronized fun checkDue(now: Long, linked: Boolean): Boolean {
        val every = when {
            // Any Wi-Fi counts as linked once a LAN host is saved, so only an answering comma keeps the fast check.
            linked && unansweredAt == null -> checkEveryMs
            recent(now, answeredAt, searchForMs) || recent(now, wokenAt, searchForMs) -> searchEveryMs
            else -> idleSearchEveryMs
        }
        if (recent(now, answeredAt, every) || recent(now, checkedAt, every)) return false
        checkedAt = now
        return true
    }
}

/** Keeps one measurement while a transport is busy; all timing uses the phone's monotonic clock. */
internal class PhoneGpsStream(private val sessionId: String = java.util.UUID.randomUUID().toString()) {
    private data class Measurement(val sequence: Long, val elapsedNanos: Long, val fix: PhoneGps.Fix)
    private var latest: Measurement? = null
    @Synchronized fun offer(fix: PhoneGps.Fix, elapsedNanos: Long, nowNanos: Long): Boolean {
        if (elapsedNanos <= 0 || elapsedNanos > nowNanos || elapsedNanos <= (latest?.elapsedNanos ?: 0)) return false
        if (PhoneGps.payload(fix, (nowNanos - elapsedNanos) / 1_000_000) == null) return false
        latest = Measurement((latest?.sequence ?: 0) + 1, elapsedNanos, fix)
        return true
    }
    @Synchronized fun sequence() = latest?.sequence ?: 0L
    @Synchronized fun payload(nowNanos: Long, extended: Boolean = true, serverMonoTimeNs: Long? = null): Pair<Long, Map<String, Any>>? {
        val measurement = latest ?: return null
        if (nowNanos < measurement.elapsedNanos) return null
        val values = PhoneGps.payload(measurement.fix, (nowNanos - measurement.elapsedNanos) / 1_000_000)?.toMutableMap() ?: return null
        if (extended) {
            values["sessionId"] = sessionId
            values["sequence"] = measurement.sequence
            values["sampleElapsedRealtimeNanos"] = measurement.elapsedNanos
            values["sentElapsedRealtimeNanos"] = nowNanos
            serverMonoTimeNs?.let { values["serverMonoTimeNs"] = it }
        }
        return measurement.sequence to values
    }
}
