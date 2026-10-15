package com.virtua.launcher.core

import android.graphics.Bitmap
import com.virtua.launcher.nativeapi.VirtuaNative
import kotlin.math.sqrt

/**
 * Working stand-in for CompactFlowNet.
 *
 * CompactFlowNet (arXiv 2412.13273, Samsung/Picsart, Dec 2024) has no public
 * code or trained weights anywhere as of Oct 2026 — it can't be streamed via
 * update_manifest.json because there is nothing to host. This uses real
 * block-matching dense motion estimation (SAD search, implemented in
 * virtua_core.cpp) instead of a stub, so "compactflow" produces an actual
 * motion field today. Swap this for a trained CompactFlow (or an equivalent
 * net with public weights) once one exists.
 */
object MotionEstimator {

    data class Result(
        val blocksX: Int,
        val blocksY: Int,
        /** Flat [dx, dy, confidence] triples, row-major. */
        val vectors: FloatArray,
        val avgMagnitudePx: Float,
        val avgConfidence: Float
    )

    private const val BLOCK_SIZE = 16
    private const val SEARCH_RADIUS = 6
    private const val TARGET_WIDTH = 160

    fun estimate(prev: Bitmap, curr: Bitmap): Result {
        val scale = TARGET_WIDTH.toFloat() / prev.width
        val w = TARGET_WIDTH
        val h = ((prev.height * scale).toInt()).coerceAtLeast(BLOCK_SIZE)

        val prevLuma = toLuma(prev, w, h)
        val currLuma = toLuma(curr, w, h)

        val vectors = VirtuaNative.nativeComputeMotionField(
            prevLuma, currLuma, w, h, BLOCK_SIZE, SEARCH_RADIUS
        )

        val blocksX = (w / BLOCK_SIZE).coerceAtLeast(1)
        val blocksY = (h / BLOCK_SIZE).coerceAtLeast(1)

        var sumMag = 0f
        var sumConf = 0f
        var count = 0
        var i = 0
        while (i + 2 < vectors.size) {
            val dx = vectors[i]
            val dy = vectors[i + 1]
            sumMag += sqrt(dx * dx + dy * dy)
            sumConf += vectors[i + 2]
            count++
            i += 3
        }

        return Result(
            blocksX, blocksY, vectors,
            avgMagnitudePx = if (count > 0) sumMag / count else 0f,
            avgConfidence = if (count > 0) sumConf / count else 0f
        )
    }

    private fun toLuma(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[idx] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return out
    }
}
