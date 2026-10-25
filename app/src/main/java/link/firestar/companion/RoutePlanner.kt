package link.firestar.companion

object RoutePlanner {
    private fun rank(route: Route) = when (route) { Route.GALAXY_WIFI, Route.HOME_NETWORK -> 0; Route.TUNNEL -> 1; Route.BLUETOOTH -> 2 }
    fun better(candidate: Route, current: Route) = rank(candidate) < rank(current)
    fun next(after: Route?, mode: String, hasLanWifi: Boolean, internetValidated: Boolean, hasTunnelCred: Boolean, hasBtPairing: Boolean): Route? {
        val candidates = listOfNotNull(if (hasLanWifi && mode in setOf("auto", "wifi")) Route.HOME_NETWORK else null,
            if (internetValidated && hasTunnelCred && mode in setOf("auto", "remote")) Route.TUNNEL else null,
            if (hasBtPairing && mode in setOf("auto", "bluetooth")) Route.BLUETOOTH else null)
        return candidates.firstOrNull { after == null || mode == "auto" && rank(it) > rank(after) }
    }
}
