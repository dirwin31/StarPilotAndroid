package com.starpilot.galaxy

object RoutePlanner {
    val DEFAULT_ORDER = listOf("wifi", "remote", "bluetooth")

    /** The saved Automatic preference order ("wifi,remote,bluetooth"), repaired so every route appears exactly once. */
    fun parse(saved: String?): List<String> {
        val kept = saved.orEmpty().split(',').filter { it in DEFAULT_ORDER }.distinct()
        return kept + DEFAULT_ORDER.filter { it !in kept }
    }
    private fun key(route: Route) = when (route) { Route.GALAXY_WIFI, Route.HOME_NETWORK -> "wifi"; Route.TUNNEL -> "remote"; Route.BLUETOOTH -> "bluetooth" }
    private fun rank(route: Route, order: List<String>) = order.indexOf(key(route))
    fun better(candidate: Route, current: Route, order: List<String> = DEFAULT_ORDER) = rank(candidate, order) < rank(current, order)
    fun next(after: Route?, mode: String, hasLanWifi: Boolean, internetValidated: Boolean, hasTunnelCred: Boolean, hasBtPairing: Boolean,
             order: List<String> = DEFAULT_ORDER): Route? {
        val candidates = order.mapNotNull {
            when (it) {
                "wifi" -> if (hasLanWifi && mode in setOf("auto", "wifi")) Route.HOME_NETWORK else null
                "remote" -> if (internetValidated && hasTunnelCred && mode in setOf("auto", "remote")) Route.TUNNEL else null
                "bluetooth" -> if (hasBtPairing && mode in setOf("auto", "bluetooth")) Route.BLUETOOTH else null
                else -> null
            }
        }
        return candidates.firstOrNull { after == null || mode == "auto" && rank(it, order) > rank(after, order) }
    }
}
