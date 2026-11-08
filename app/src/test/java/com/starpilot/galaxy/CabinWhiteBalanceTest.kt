package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class CabinWhiteBalanceTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test fun magentaCastIsNeutralized() {
        val pixels = IntArray(16 * 16) { rgb(170, 110, 160) }
        CabinWhiteBalance.apply(pixels, 16, 16)
        val (r, g, b) = channels(pixels[0]).toList()
        assertTrue(maxOf(r, g, b) - minOf(r, g, b) < 10)
        assertEquals(0xFF, pixels[0] ushr 24)
    }
    @Test fun darkFramesAndClippedHighlightsKeepTheirGains() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CabinWhiteBalance.gains(IntArray(64) { rgb(2, 1, 2) }, 8, 8), 0f)
        val pixels = IntArray(64) { if (it == 0) rgb(255, 255, 255) else rgb(170, 110, 160) }
        CabinWhiteBalance.apply(pixels, 8, 8)
        assertArrayEquals(intArrayOf(255, 255, 255), channels(pixels[0]))
    }
}
