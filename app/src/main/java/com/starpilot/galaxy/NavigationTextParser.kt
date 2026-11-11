package com.starpilot.galaxy

/** Shared English notification text grammar; arrow icons are matched separately (NavigationIconMemory). */
internal object NavigationTextParser {
    private val distance = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*(km|m|mi|ft|yd)\b(?:\s*[-·•–—]\s*|\s+)?(.*)$""", RegexOption.IGNORE_CASE)
    private val summary = Regex("""\b(ETA|arrival|arrive at|remaining|hr|min)\b|\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE)
    private val rerouting = Regex("""\b(rerouting|recalculating|finding (?:a |the )?(?:best )?route|searching for GPS|GPS signal lost|navigation (?:paused|ended))\b""", RegexOption.IGNORE_CASE)
    private val maneuver = Regex("""\b(turn|keep|exit|ramp|fork|merge|roundabout|u-turn|continue|straight|head)\b""", RegexOption.IGNORE_CASE)
    private fun clean(value: String) = value.replace(Regex("""[\s\p{Zs}]+"""), " ").trim().take(500)

    fun parse(title: String, text: String, bigText: String = "", lines: List<String> = emptyList(),
              viewTitle: String = "", viewDescription: String = ""): NavigationGuidance? {
        val values = (listOf(viewTitle, viewDescription, title, text, bigText) + lines).map(::clean).filter { it.isNotEmpty() }.distinct()
        if (values.any { rerouting.containsMatchIn(it) }) return null
        // Next-maneuver distances occur in the title, not the trip/ETA summary.
        val match = listOf(viewTitle, title).map(::clean).firstNotNullOfOrNull { distance.matchEntire(it) }
        val meters = match?.let {
            val raw = it.groupValues[1]
            // A comma/dot followed by three digits may be a thousands separator.
            // Do not turn "1,000 m" into one meter when the locale is ambiguous.
            if (it.groupValues[2].lowercase() in setOf("m", "ft", "yd") && Regex("""[.,]\d{3}$""").containsMatchIn(raw)) return@let null
            val amount = raw.replace(',', '.').toDoubleOrNull() ?: return@let null
            val scale = when (it.groupValues[2].lowercase()) { "km" -> 1000.0; "mi" -> 1609.344; "ft" -> 0.3048; "yd" -> 0.9144; else -> 1.0 }
            (amount * scale).takeIf { value -> value.isFinite() && value in 0.0..1_000_000.0 }
        }
        val trailing = match?.groupValues?.get(3).orEmpty()
        val candidates = listOf(viewDescription, trailing, if (match == null) title else "", text, bigText) + lines
        val usable = candidates.map(::clean).filter {
            it.isNotEmpty() && !summary.containsMatchIn(it) && distance.matchEntire(it) == null &&
                !it.startsWith("then ", ignoreCase = true) && (meters != null || maneuver.containsMatchIn(it))
        }
        // Maps may show the bare road in one field and the maneuver in another. Take
        // the whole step from a field naming a maneuver, never a later "Then" step.
        val instruction = usable.firstOrNull { maneuver.containsMatchIn(it) } ?: usable.firstOrNull() ?: return null
        val prefix = instruction.lowercase().substringBefore(" onto ").substringBefore(" toward ").substringBefore(" towards ")
        val lower = prefix.substringBefore(" on ")
        val side = Regex("""\b(left|right)\b""").find(lower)?.value
            ?: Regex("""\bon the (left|right)\b""").find(prefix)?.groupValues?.get(1).orEmpty()
        val modifier = when {
            Regex("""\bu[- ]?turn\b""").containsMatchIn(lower) -> "uturn"
            side.isNotEmpty() && "sharp" in lower -> "sharp" + side.replaceFirstChar { it.uppercase() }
            side.isNotEmpty() && ("slight" in lower || "keep" in lower) -> "slight" + side.replaceFirstChar { it.uppercase() }
            Regex("""\b(straight|straight ahead)\b""").containsMatchIn(lower) -> "straight"
            else -> side
        }
        val kind = when {
            "roundabout" in lower -> "roundabout"
            Regex("""\b(?:take|use|enter) (?:the )?(?:left |right )?ramp\b""").containsMatchIn(lower) -> "on ramp"
            Regex("""\bexit\b""").containsMatchIn(lower) -> "off ramp"
            "merge" in lower -> "merge"
            Regex("""\b(keep|fork)\b""").containsMatchIn(lower) -> "fork"
            "turn" in lower || modifier == "uturn" || (side.isNotEmpty() && Regex("""^(make|take|bear)\b""").containsMatchIn(lower)) -> "turn"
            "continue" in lower || modifier == "straight" || Regex("""^head\b""").containsMatchIn(lower) -> "continue"
            else -> ""
        }
        val road = Regex("""\b(?:onto)\s+(.+)$""", RegexOption.IGNORE_CASE).find(instruction)?.groupValues?.get(1)
        val destination = Regex("""\btowards?\s+(.+)$""", RegexOption.IGNORE_CASE).find(instruction)?.groupValues?.get(1)
        val highwayExit = Regex("""\bexit\s+([0-9]+[A-Za-z]?)\b""", RegexOption.IGNORE_CASE).find(prefix)?.groupValues?.get(1)?.uppercase()
        val ordinal = Regex("""\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|[0-9]+(?:st|nd|rd|th)?)\s+exit\b""",
            RegexOption.IGNORE_CASE).find(prefix)?.groupValues?.get(1)?.lowercase()
        val roundaboutExit = ordinal?.let {
            val words = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
            val index = words.indexOf(it)
            if (index >= 0) (index + 1).toString() else it.takeWhile(Char::isDigit).toIntOrNull()?.takeIf { n -> n > 0 }?.toString()
        }
        val exit = if (kind == "roundabout") roundaboutExit else highwayExit
        return NavigationGuidance(instruction, meters, kind, if (kind.isEmpty()) "" else modifier, road, exit, destination)
    }
}
