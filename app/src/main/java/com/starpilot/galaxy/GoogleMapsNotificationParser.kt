package com.starpilot.galaxy

/** Text parsing; icon-only turns are filled by NavigationIconMemory once learned. No Maps private API. */
internal object GoogleMapsNotificationParser : NavigationNotificationParser {
    override val source = "googleMaps"
    override val packageName = "com.google.android.apps.maps"
    override val viewFields = setOf("nav_title", "nav_description", "lockscreen_directions", "title")
    override fun parse(text: NavigationNotificationText) = parse(text.title, text.text, text.bigText, text.lines,
        text.viewFields["nav_title"] ?: text.viewFields["lockscreen_directions"] ?: text.viewFields["title"].orEmpty(),
        text.viewFields["nav_description"].orEmpty())
    fun parse(title: String, text: String, bigText: String = "", lines: List<String> = emptyList(),
              viewTitle: String = "", viewDescription: String = "") =
        NavigationTextParser.parse(title, text, bigText, lines, viewTitle, viewDescription)
}
