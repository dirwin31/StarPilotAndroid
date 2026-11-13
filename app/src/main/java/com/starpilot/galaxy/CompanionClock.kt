package com.starpilot.galaxy

/** Advances the server's probe timestamp only by time elapsed after the response arrived. */
internal class CompanionClock {
    private var key: Any? = null
    private var receivedAt = 0L
    private var serverAt: Long? = null
    fun needsProbe(connection: Any, now: Long) = key != connection || now < receivedAt || now - receivedAt >= 60_000_000_000L
    fun record(connection: Any, server: Long?, received: Long) {
        key = connection; serverAt = server; receivedAt = received
    }
    fun time(connection: Any, now: Long): Long? {
        if (needsProbe(connection, now)) return null
        val base = serverAt ?: return null
        val elapsed = now - receivedAt
        return if (base > Long.MAX_VALUE - elapsed) null else base + elapsed
    }
    fun clear() { key = null; serverAt = null }
}
