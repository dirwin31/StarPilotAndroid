package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class NavigationIconMemoryTest {
    /** A stem from the bottom center plus a bar toward one side: a crude turn arrow. */
    private fun arrow(size: Int, right: Boolean, ink: Int = 0xFFFFFFFF.toInt(), paper: Int = 0): IntArray =
        IntArray(size * size) { i ->
            val x = i % size * 48 / size; val y = i / size * 48 / size
            val stem = x in 20..27 && y in 18..44
            val bar = y in 12..19 && if (right) x in 20..42 else x in 5..27
            val head = if (right) x in 36..42 && y in 6..25 else x in 5..11 && y in 6..25
            if (stem || bar || head) ink else paper
        }

    private val road = NavigationGuidance("US-95 N", 804.0)
    private val left = NavigationGuidance("Turn left onto Main St", 200.0, "turn", "left")

    @Test fun shapesAreStableAcrossSizeAndBackground() {
        val small = NavigationIconFingerprint.of(arrow(48, false), 48, 48)!!
        val large = NavigationIconFingerprint.of(arrow(96, false), 96, 96)!!
        val badge = NavigationIconFingerprint.of(arrow(48, false, paper = 0xFF1A73E8.toInt()), 48, 48)!!
        val mirrored = NavigationIconFingerprint.of(arrow(48, true), 48, 48)!!
        assertTrue(NavigationIconFingerprint.distance(small, large) <= NavigationIconMemory.MATCH_BITS)
        assertTrue(NavigationIconFingerprint.distance(small, badge) <= NavigationIconMemory.MATCH_BITS)
        assertTrue(NavigationIconFingerprint.distance(small, mirrored) > NavigationIconMemory.MATCH_BITS * 2)
    }

    @Test fun blankIconsHaveNoFingerprint() {
        assertNull(NavigationIconFingerprint.of(IntArray(48 * 48), 48, 48))
        assertNull(NavigationIconFingerprint.of(IntArray(48 * 48) { 0xFF1A73E8.toInt() }, 48, 48))
        assertNull(NavigationIconFingerprint.of(IntArray(4), 2, 2))
    }

    @Test fun learnsFromTextThenFillsIconOnlyGuidance() {
        val saved = mutableMapOf<String, String>()
        val memory = NavigationIconMemory { put, removed -> saved.putAll(put); removed.forEach(saved::remove) }
        val hash = NavigationIconFingerprint.of(arrow(48, false), 48, 48)!!
        assertEquals("unlearned arrow", memory.resolve("googleMaps", hash, road).second)
        assertEquals(left to "learned turn|left", memory.resolve("googleMaps", hash, left))
        val (nav, result) = memory.resolve("googleMaps", NavigationIconFingerprint.of(arrow(96, false), 96, 96), road)
        assertEquals("matched turn|left", result)
        assertEquals("turn", nav!!.maneuverType)
        assertEquals("left", nav.modifier)
        assertEquals("US-95 N", nav.instruction)
        // Learning is per app and survives a restart through the persisted table.
        assertEquals("unlearned arrow", memory.resolve("waze", hash, road).second)
        assertEquals("matched turn|left", NavigationIconMemory(saved).resolve("googleMaps", hash, road).second)
    }

    @Test fun conflictingOrAmbiguousShapesStayUnknown() {
        val memory = NavigationIconMemory()
        val hash = NavigationIconFingerprint.of(arrow(48, false), 48, 48)!!
        memory.resolve("googleMaps", hash, left)
        assertEquals("conflicting labels; arrow ignored",
            memory.resolve("googleMaps", hash, left.copy(modifier = "slightLeft")).second)
        assertEquals(road to "ambiguous arrow", memory.resolve("googleMaps", hash, road))
    }

    @Test fun onlyStarPilotVocabularyIsLearnedOrLoaded() {
        val hash = NavigationIconFingerprint.of(arrow(48, false), 48, 48)!!
        val memory = NavigationIconMemory(mapOf("googleMaps:$hash" to "teleport|left"))
        assertEquals("unlearned arrow", memory.resolve("googleMaps", hash, road).second)
        assertEquals("text maneuver; not learned",
            memory.resolve("googleMaps", hash, NavigationGuidance("At the roundabout", 50.0, "roundabout", "")).second)
        assertEquals("unlearned arrow", memory.resolve("googleMaps", hash, road).second)
        assertEquals(null to "no guidance", memory.resolve("googleMaps", hash, null))
        assertEquals(road to "no arrow icon", memory.resolve("googleMaps", null, road))
    }

    @Test fun tableIsBounded() {
        val removed = mutableListOf<String>()
        val memory = NavigationIconMemory { _, gone -> removed.addAll(gone) }
        for (i in 0..NavigationIconMemory.CAPACITY) memory.resolve("googleMaps", i.toString(16).padStart(64, '0'), left)
        assertEquals(listOf("googleMaps:" + "0".padStart(64, '0')), removed)
    }
}
