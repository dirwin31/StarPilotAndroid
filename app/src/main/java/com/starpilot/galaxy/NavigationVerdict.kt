package com.starpilot.galaxy

/** What the comma reports doing with the forwarded guidance, in plain words. */
internal object NavigationVerdict {
    private val MAP = mapOf(
        "mapUnavailable" to "its map has no junctions here",
        "mapNoNearbyJunction" to "its map has no junction at that distance",
        "mapUnverifiable" to "this step cannot be checked on its map",
        "mapDifferentDirection" to "the junction on its map turns another way",
        "mapAmbiguous" to "several junctions on its map match",
    )

    fun describe(reason: String, mapStatus: String, mapReason: String): String = when (reason) {
        "routeAgreed" -> "Matches the comma's own route, which controls the turn"
        "routeConflict" -> "Differs from the comma's own route, which still controls the turn"
        "mapboxPriority" -> "The comma's own route is guiding"
        "disabled" -> "Phone navigation is off on the comma"
        "notificationGuidance" -> when (mapStatus) {
            "mapAligned", "mapInferred" -> "Turn confirmed on the comma's map: it can slow the car and assist a signalled turn"
            "" -> "Shown on the comma"
            else -> "Shown on the comma only: " + (MAP[mapStatus] ?: mapStatus) + if (mapReason.isEmpty()) "" else " ($mapReason)"
        }
        else -> "Not used by the comma ($reason)"
    }
}
