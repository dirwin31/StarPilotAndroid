package com.starpilot.galaxy

/** A delayed post/removal for an old notification must not resurrect or clear its replacement. */
internal class NavigationObservationOrder {
    private val removed = linkedMapOf<String, Long>()
    fun accepts(key: String, postTime: Long) = postTime > (removed[key] ?: Long.MIN_VALUE)
    fun removed(key: String, postTime: Long, oldestAllowed: Long) {
        removed.entries.removeAll { it.value < oldestAllowed }
        removed[key] = maxOf(postTime, removed[key] ?: Long.MIN_VALUE)
    }
}
