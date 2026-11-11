package com.starpilot.galaxy

/** Shared English notification text grammar; arrow icons are matched separately (NavigationIconMemory). */
internal object NavigationTextParser {
    private val distance = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*(km|m|mi|ft|yd)\b(?:\s*[-·•–—]\s*|\s+)?(.*)$""", RegexOption.IGNORE_CASE)
    private val summary = Regex("""\b(ETA|arrival|remaining|hr|min)\b|\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE)
    private val rerouting = Regex("""\b(rerouting|recalculating|finding (?:a |the )?(?:best )?route|searching for GPS|GPS signal lost|navigation (?:paused|ended))\b""", RegexOption.IGNORE_CASE)
    private val maneuver = Regex("""\b(turn|keep|exit|ramp|fork|merge|roundabout|u-turn|continue|straight|head)\b""", RegexOption.IGNORE_CASE)
    private val arrival = Regex("""^(?:(?:you(?: have|'ve|’ve)? )?arrived?\b|(?:your )?destination (?:is|will be) on (?:the|your) (?:left|right)\b)""", RegexOption.IGNORE_CASE)
    private val clockOrDuration = Regex("""\d{1,2}:\d{2}|\b\d+\s*(?:h|hrs?|hours?|min|mins|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val parts = Regex("""[\s\p{Zs}]*[·•|\n][\s\p{Zs}]*|[\s\p{Zs}]+[-–—][\s\p{Zs}]+|,[\s\p{Zs}]+""")
    private val tripDuration = Regex("""^(?:(\d{1,2})\s*(?:d|days?))?\s*(?:(\d{1,3})\s*(?:h|hrs?|hours?))?\s*(?:(\d{1,3})\s*(?:min|mins|minutes?))?$""", RegexOption.IGNORE_CASE)
    private val tripDistance = Regex("""^([0-9]+(?:[.,][0-9]+)?)\s*(km|m|mi|ft|yd)$""", RegexOption.IGNORE_CASE)
    private val eta = Regex("""^(?:ETA|arrival|arrive (?:at|by))?\s*\d{1,2}:\d{2}(?:\s*[ap]\.?\s*m\.?)?(?:\s+ETA)?$""", RegexOption.IGNORE_CASE)
    private fun clean(value: String) = value.replace(Regex("""[\s\p{Zs}]+"""), " ").trim().take(500)
    private fun arrives(value: String) = arrival.containsMatchIn(value) && !clockOrDuration.containsMatchIn(value)

    /** A comma/dot followed by three digits may be a thousands separator; ambiguous amounts stay unknown. */
    private fun toMeters(raw: String, unit: String, max: Double, strict: Boolean = false): Double? {
        val lower = unit.lowercase()
        if ((strict || lower in setOf("m", "ft", "yd")) && Regex("""[.,]\d{3}$""").containsMatchIn(raw)) return null
        val amount = raw.replace(',', '.').toDoubleOrNull() ?: return null
        val scale = when (lower) { "km" -> 1000.0; "mi" -> 1609.344; "ft" -> 0.3048; "yd" -> 0.9144; else -> 1.0 }
        return (amount * scale).takeIf { it.isFinite() && it in 0.0..max }
    }
    private fun seconds(part: String): Long? {
        val match = tripDuration.matchEntire(part) ?: return null
        val (days, hours, minutes) = match.destructured
        if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty()) return null
        return ((days.toLongOrNull() ?: 0L) * 86_400 + (hours.toLongOrNull() ?: 0L) * 3600 + (minutes.toLongOrNull() ?: 0L) * 60)
            .takeIf { it <= 604_800 }
    }

    /** Remaining trip from summary fields only (never the title, which holds the next maneuver).
     * Requires exactly one duration and one distance; a lone distance counts only from subText or lines.
     */
    internal fun trip(text: String, bigText: String = "", lines: List<String> = emptyList(), subText: String = ""): Pair<Double, Long>? {
        val durations = mutableSetOf<Long>(); val distances = mutableSetOf<Double>(); val lone = mutableSetOf<Double>()
        var ambiguous = false
        for ((value, loose) in listOf(text to false, bigText to false, subText to true) + lines.map { it to true }) {
            val pieces = value.split(parts).map(::clean).filter { it.isNotEmpty() }
            fun distanceOf(part: String): Double? = tripDistance.matchEntire(part)?.let {
                toMeters(it.groupValues[1], it.groupValues[2], 20_000_000.0, strict = true) ?: run { ambiguous = true; null }
            }
            if (pieces.any { seconds(it) != null || eta.matches(it) }) pieces.forEach { part ->
                seconds(part)?.let { durations.add(it) }
                distanceOf(part)?.let { distances.add(it) }
            } else if (loose && pieces.size == 1) distanceOf(pieces[0])?.let { lone.add(it) }
        }
        val chosen = distances.ifEmpty { lone }
        if (ambiguous || durations.size != 1 || chosen.size != 1) return null
        return chosen.first() to durations.first()
    }

    fun parse(title: String, text: String, bigText: String = "", lines: List<String> = emptyList(),
              viewTitle: String = "", viewDescription: String = "", subText: String = ""): NavigationGuidance? {
        val values = (listOf(viewTitle, viewDescription, title, text, bigText, subText) + lines).map(::clean).filter { it.isNotEmpty() }.distinct()
        if (values.any { rerouting.containsMatchIn(it) }) return null
        // Next-maneuver distances occur in the title, not the trip/ETA summary.
        val match = listOf(viewTitle, title).map(::clean).firstNotNullOfOrNull { distance.matchEntire(it) }
        val meters = match?.let { toMeters(it.groupValues[1], it.groupValues[2], 1_000_000.0) }
        val trailing = match?.groupValues?.get(3).orEmpty()
        val candidates = listOf(viewDescription, trailing, if (match == null) title else "", text, bigText) + lines
        val usable = candidates.map(::clean).filter {
            it.isNotEmpty() && distance.matchEntire(it) == null && (arrives(it) || !summary.containsMatchIn(it) &&
                !it.startsWith("then ", ignoreCase = true) && (meters != null || maneuver.containsMatchIn(it)))
        }
        // Maps may show the bare road in one field and the maneuver in another. Take
        // the whole step from a field naming a maneuver, never a later "Then" step.
        val instruction = usable.firstOrNull { arrives(it) || maneuver.containsMatchIn(it) } ?: usable.firstOrNull() ?: return null
        val arriving = arrives(instruction)
        val prefix = instruction.lowercase().substringBefore(" onto ").substringBefore(" toward ").substringBefore(" towards ")
        val lower = prefix.substringBefore(" on ")
        val side = Regex("""\b(left|right)\b""").find(lower)?.value
            ?: Regex("""\bon the (left|right)\b""").find(prefix)?.groupValues?.get(1).orEmpty()
        val modifier = when {
            // Arrival sides come only from "on the left/right", never from a place name.
            arriving -> Regex("""\bon (?:the|your) (left|right)\b""").find(instruction.lowercase())?.groupValues?.get(1).orEmpty()
            Regex("""\bu[- ]?turn\b""").containsMatchIn(lower) -> "uturn"
            side.isNotEmpty() && "sharp" in lower -> "sharp" + side.replaceFirstChar { it.uppercase() }
            side.isNotEmpty() && ("slight" in lower || "keep" in lower) -> "slight" + side.replaceFirstChar { it.uppercase() }
            Regex("""\b(straight|straight ahead)\b""").containsMatchIn(lower) -> "straight"
            else -> side
        }
        val kind = when {
            arriving -> "arrive"
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
        val exit = when (kind) { "roundabout" -> roundaboutExit; "arrive" -> null; else -> highwayExit }
        val remaining = trip(text, bigText, lines, subText)
        return NavigationGuidance(instruction, meters, kind, if (kind.isEmpty()) "" else modifier, road, exit, destination,
            remaining?.first, remaining?.second)
    }
}
