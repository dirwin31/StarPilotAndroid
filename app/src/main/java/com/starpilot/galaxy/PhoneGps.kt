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
