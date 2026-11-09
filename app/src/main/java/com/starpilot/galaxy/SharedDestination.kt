package com.starpilot.galaxy

import java.net.URLDecoder

/** A place shared from Google or Apple Maps: its coordinates when the shared text carries them, else words to search for. */
sealed class SharedDestination {
    data class Place(val name: String, val latitude: Double, val longitude: Double) : SharedDestination()
    data class Search(val text: String) : SharedDestination()

    companion object {
        const val MAX_TEXT = 8192
        private const val NUMBER = "(-?\\d+(?:\\.\\d+)?)"
        private val PATTERNS = listOf(Regex("!3d$NUMBER!4d$NUMBER"), Regex("[?&:](?:q|query|daddr|destination|ll)=$NUMBER,\\s*$NUMBER"),
            Regex("geo:$NUMBER,\\s*$NUMBER"), Regex("@$NUMBER,\\s*$NUMBER"))
        val SHORT_LINK = Regex("https://(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps)/[A-Za-z0-9_-]{1,64}")

        /** Google Maps often puts the place name only in the subject. */
        fun shareText(subject: String?, text: String): String {
            val name = subject?.trim().orEmpty()
            return (if (name.isEmpty() || text.contains(name)) text else "$name\n$text").take(MAX_TEXT)
        }

        fun isGoogleMapsHost(host: String) = host == "maps.app.goo.gl" || host == "goo.gl" || host == "google.com" || host.endsWith(".google.com")

        /** Destination coordinates take precedence over a map's viewport (@lat,lng). */
        fun parse(input: String): SharedDestination {
            val trimmed = input.take(MAX_TEXT).trim()
            val text = try { URLDecoder.decode(trimmed, "UTF-8") } catch (_: IllegalArgumentException) { trimmed.replace('+', ' ') }
            for (pattern in PATTERNS) {
                val match = pattern.find(text) ?: continue
                val latitude = match.groupValues[1].toDoubleOrNull() ?: continue
                val longitude = match.groupValues[2].toDoubleOrNull() ?: continue
                if (kotlin.math.abs(latitude) > 90 || kotlin.math.abs(longitude) > 180) continue
                // geo:0,0?q=an address is a search, not a destination in the ocean.
                if (match.value.startsWith("geo:0,0") && Regex("[?&:]q=").containsMatchIn(text)) continue
                return Place(label(text) ?: "Shared destination", latitude, longitude)
            }
            Regex("[?&:](?:q|query|daddr|destination)=([^&#\\n]+)").find(text)?.let { return Search(it.groupValues[1].trim().take(200)) }
            return Search(words(text).take(200))
        }

        private fun label(text: String): String? {
            Regex("\\(([^()\\n]{1,120})\\)").find(text)?.let { return it.groupValues[1] }
            Regex("/place/([^/@?#\\n]{1,120})").find(text)?.let { return it.groupValues[1].trim() }
            Regex("[?&]q=([^&#\\n]{1,120})").find(text)?.let { if (it.groupValues[1].split(',').first().toDoubleOrNull() == null) return it.groupValues[1] }
            val lead = words(text).split(',').first()
            return lead.ifEmpty { null }?.take(120)
        }

        /** The shared text without its links: Google's "Name\nAddress\nlink" becomes "Name, Address". */
        private fun words(text: String) = text.lines()
            .map { line -> line.split(' ').filter { it.isNotEmpty() && "://" !in it }.joinToString(" ").trim() }
            .filter { it.isNotEmpty() }.joinToString(", ")
    }
}
