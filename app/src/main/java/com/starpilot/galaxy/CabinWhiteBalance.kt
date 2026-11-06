package com.starpilot.galaxy

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Same correction as Galaxy's white-balance.js: cancels the cabin camera's IR magenta cast. */
object CabinWhiteBalance {
    fun gains(pixels: IntArray, width: Int, height: Int): FloatArray {
        var r = 0.0; var g = 0.0; var b = 0.0; var count = 0
        for (y in 0 until height step 8) for (x in 0 until width step 8) {
            val c = pixels[y * width + x]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; count++
        }
        if (count == 0) return floatArrayOf(1f, 1f, 1f)
        val mean = doubleArrayOf(r / count / 255, g / count / 255, b / count / 255)
        val luma = 0.299 * mean[0] + 0.587 * mean[1] + 0.114 * mean[2]
        if (luma < 0.02) return floatArrayOf(1f, 1f, 1f)
        return FloatArray(3) { min(1.6, max(0.6, luma / max(mean[it], 1e-3))).toFloat() }
    }

    fun apply(pixels: IntArray, width: Int, height: Int) {
        val gains = gains(pixels, width, height)
        val rgb = FloatArray(3)
        for (i in pixels.indices) {
            val c = pixels[i]
            rgb[0] = ((c shr 16) and 0xFF) / 255f; rgb[1] = ((c shr 8) and 0xFF) / 255f; rgb[2] = (c and 0xFF) / 255f
            val t = min(1f, max(0f, (max(rgb[0], max(rgb[1], rgb[2])) - 0.95f) / 0.05f))
            for (k in 0..2) rgb[k] = rgb[k] * gains[k] * (1 - t) + rgb[k] * t
            val gray = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
            val out = IntArray(3) { (min(1f, max(0f, gray + (rgb[it] - gray) * 0.75f)) * 255).roundToInt() }
            pixels[i] = (c and 0xFF000000.toInt()) or (out[0] shl 16) or (out[1] shl 8) or out[2]
        }
    }

    fun apply(bitmap: Bitmap): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        apply(pixels, copy.width, copy.height)
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }
}
