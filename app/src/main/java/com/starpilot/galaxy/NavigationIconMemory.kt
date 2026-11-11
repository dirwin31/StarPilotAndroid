package com.starpilot.galaxy

/** Google Maps often shows only distance + road; the direction is in the arrow icon.
 * Icons are reduced to a 16x16 shape fingerprint (no pixels or text are kept).
 */
internal object NavigationIconFingerprint {
    private const val GRID = 16

    /** ARGB pixels -> 64 hex chars of foreground shape, or null for a blank/featureless icon. */
    fun of(pixels: IntArray, width: Int, height: Int): String? {
        if (width < GRID || height < GRID || pixels.size != width * height) return null
        val border = (0 until width).flatMap { listOf(pixels[it], pixels[(height - 1) * width + it]) } +
            (0 until height).flatMap { listOf(pixels[it * width], pixels[it * width + width - 1]) }
        val opaque = border.filter { it ushr 24 >= 128 }
        // Transparent artwork uses alpha; a filled badge uses contrast with its own background.
        val background = if (opaque.size * 2 < border.size) null else opaque.groupingBy { it and 0xFFFFFF }.eachCount().maxBy { it.value }.key
        fun foreground(px: Int): Boolean {
            if (px ushr 24 < 128) return false
            background ?: return true
            return listOf(16, 8, 0).sumOf { kotlin.math.abs((px shr it and 0xFF) - (background shr it and 0xFF)) } > 96
        }
        val mask = BooleanArray(pixels.size) { foreground(pixels[it]) }
        val xs = (0 until width).filter { x -> (0 until height).any { mask[it * width + x] } }
        val ys = (0 until height).filter { y -> (0 until width).any { mask[y * width + it] } }
        if (xs.isEmpty() || mask.count { it } * 100 < pixels.size) return null
        // A square box around the shape keeps slight/sharp proportions independent of icon size.
        val side = maxOf(xs.last() - xs.first(), ys.last() - ys.first()) + 1
        val left = (xs.first() + xs.last() + 1 - side) / 2.0
        val top = (ys.first() + ys.last() + 1 - side) / 2.0
        val bits = BooleanArray(GRID * GRID) { cell ->
            val x0 = left + side * (cell % GRID) / GRID.toDouble()
            val y0 = top + side * (cell / GRID) / GRID.toDouble()
            var hit = 0; var total = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val x = (x0 + side * (sx + .5) / (GRID * 4)).toInt()
                val y = (y0 + side * (sy + .5) / (GRID * 4)).toInt()
                total++
                if (x in 0 until width && y in 0 until height && mask[y * width + x]) hit++
            }
            hit * 3 >= total
        }
        return bits.toList().chunked(4).joinToString("") { nibble ->
            nibble.fold(0) { acc, bit -> acc * 2 + if (bit) 1 else 0 }.toString(16)
        }
    }

    fun distance(a: String, b: String): Int =
        if (a.length != b.length) Int.MAX_VALUE
        else a.indices.sumOf { Integer.bitCount(a[it].digitToInt(16) xor b[it].digitToInt(16)) }
}

/** Learns fingerprint -> maneuver only from notifications whose own text named the turn.
 * Ambiguous or conflicting shapes stay unknown; labels stay inside StarPilot's accepted vocabulary.
 */
internal class NavigationIconMemory(
    initial: Map<String, String> = emptyMap(),
    private val persist: (put: Map<String, String>, removed: Collection<String>) -> Unit = { _, _ -> },
) {
    companion object {
        const val MATCH_BITS = 20
        const val CAPACITY = 96
        private const val CONFLICT = "?"
        // Mirrors openpilot/starpilot/navigation/phone.py TYPES/MODIFIERS; anything else is rejected there.
        val TYPES = setOf("turn", "continue", "on ramp", "off ramp", "fork", "merge", "roundabout")
        val MODIFIERS = setOf("left", "right", "straight", "slightLeft", "slightRight", "sharpLeft", "sharpRight", "uturn")
    }
    private val table = LinkedHashMap<String, String>().apply {
        initial.forEach { (key, value) -> if (value == CONFLICT || label(value) != null) put(key, value) }
    }

    private fun label(value: String) = value.split('|').takeIf { it.size == 2 && it[0] in TYPES && it[1] in MODIFIERS }

    /** Returns guidance (text maneuver, or a learned one when text has none) and a diagnostic. */
    fun resolve(source: String, hash: String?, guidance: NavigationGuidance?): Pair<NavigationGuidance?, String> {
        if (guidance == null) return null to "no guidance"
        hash ?: return guidance to "no arrow icon"
        val key = "$source:$hash"
        if (guidance.maneuverType.isNotEmpty()) {
            if (guidance.maneuverType !in TYPES || guidance.modifier !in MODIFIERS) return guidance to "text maneuver; not learned"
            val value = guidance.maneuverType + "|" + guidance.modifier
            val old = table[key]
            val stored = if (old == null || old == value) value else CONFLICT
            table.remove(key)
            table[key] = stored
            val evicted = if (table.size > CAPACITY) table.keys.first().also { table.remove(it) } else null
            if (old != stored || evicted != null) persist(mapOf(key to stored), listOfNotNull(evicted))
            return guidance to if (stored == CONFLICT) "conflicting labels; arrow ignored" else "learned $value"
        }
        val prefix = "$source:"
        val near = table.filter { (k, _) -> k.startsWith(prefix) && NavigationIconFingerprint.distance(k.substring(prefix.length), hash) <= MATCH_BITS }
        val values = near.values.toSet()
        if (values.isEmpty()) return guidance to "unlearned arrow"
        val parts = values.singleOrNull()?.let(::label) ?: return guidance to "ambiguous arrow"
        return guidance.copy(maneuverType = parts[0], modifier = parts[1]) to "matched ${parts[0]}|${parts[1]}"
    }
}
