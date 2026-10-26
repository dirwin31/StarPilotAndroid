package com.starpilot.galaxy

class SentryDedupe(ids: List<String> = emptyList()) {
    private val seen = LinkedHashSet(ids.takeLast(100))
    @Synchronized fun accept(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > 100) seen.remove(seen.first())
        return true
    }
    @Synchronized fun ids() = seen.toList()
}
