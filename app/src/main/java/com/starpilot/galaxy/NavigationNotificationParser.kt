package com.starpilot.galaxy

/** App-specific parsing consumes text only; lifecycle and transports never depend on the app. */
internal data class NavigationNotificationText(
    val title: String = "", val text: String = "", val bigText: String = "",
    val lines: List<String> = emptyList(), val viewFields: Map<String, String> = emptyMap(),
)

internal data class NavigationGuidance(
    val instruction: String, val distanceMeters: Double?, val maneuverType: String = "",
    val modifier: String = "", val roadName: String? = null, val exitNumber: String? = null,
    val destination: String? = null,
)

internal interface NavigationNotificationParser {
    val source: String
    val packageName: String
    val viewFields: Set<String> get() = emptySet()
    fun parse(text: NavigationNotificationText): NavigationGuidance?
}

/** Registration order is explicit priority, keeping concurrent app updates from alternating sources. */
internal class NavigationNotificationParsers(parsers: List<NavigationNotificationParser>) {
    private val registered = parsers.toList()
    init {
        require(registered.map { it.packageName }.distinct().size == registered.size)
        require(registered.map { it.source }.distinct().size == registered.size)
    }
    fun forPackage(packageName: String) = registered.firstOrNull { it.packageName == packageName }
    fun priority(source: String) = registered.indexOfFirst { it.source == source }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    companion object {
        val DEFAULT = NavigationNotificationParsers(listOf(GoogleMapsNotificationParser, StandardNavigationNotificationParser("waze", "com.waze")))
    }
}

/** Accept only explicit English standard-field guidance, regardless of app layout.
 * Missing/icon-only/unsupported instructions cannot reuse an earlier maneuver.
 */
internal class StandardNavigationNotificationParser(
    override val source: String, override val packageName: String,
) : NavigationNotificationParser {
    override fun parse(text: NavigationNotificationText): NavigationGuidance? =
        NavigationTextParser.parse(text.title, text.text, text.bigText, text.lines)
            ?.takeIf { it.maneuverType.isNotEmpty() && it.distanceMeters != null }
}
