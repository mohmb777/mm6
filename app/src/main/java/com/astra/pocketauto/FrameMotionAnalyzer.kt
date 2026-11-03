package com.astra.pocketauto

import android.media.Image
import kotlin.math.abs

/**
 * Lightweight hybrid visual stage:
 * - samples a small grid in the central screen region
 * - compares luminance against the previous frame
 *
 * It measures visual change only. It does not identify candles, prices,
 * CALL/PUT signals, or trading opportunities.
 */
class FrameMotionAnalyzer(
    private val columns: Int = 24,
    private val rows: Int = 18
) {
    private var previousSamples: IntArray? = null

    data class Result(
        val motionScore: Float,
        val activity: String,
        val sampledPoints: Int
    )

    fun analyze(image: Image): Result {
        val plane = image.planes.firstOrNull()
            ?: return Result(0f, "لا توجد بيانات", 0)

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val samples = IntArray(columns * rows)
        var index = 0

        // Central 70% of the screen: a starting observation area, not chart calibration.
        val left = (width * 0.15f).toInt()
        val right = (width * 0.85f).toInt()
        val top = (height * 0.18f).toInt()
        val bottom = (height * 0.82f).toInt()

        for (gy in 0 until rows) {
            val y = (top + (bottom - top) * gy / (rows - 1).coerceAtLeast(1))
                .coerceIn(0, height - 1)
            for (gx in 0 until columns) {
                val x = (left + (right - left) * gx / (columns - 1).coerceAtLeast(1))
                    .coerceIn(0, width - 1)
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 < buffer.limit()) {
                    val red = buffer.get(offset).toInt() and 0xFF
                    val green = buffer.get(offset + 1).toInt() and 0xFF
                    val blue = buffer.get(offset + 2).toInt() and 0xFF
                    samples[index++] = (red * 299 + green * 587 + blue * 114) / 1000
                } else {
                    samples[index++] = 0
                }
            }
        }

        val previous = previousSamples
        previousSamples = samples

        if (previous == null || previous.size != samples.size) {
            return Result(0f, "تهيئة المقارنة", samples.size)
        }

        var totalDifference = 0L
        for (i in samples.indices) {
            totalDifference += abs(samples[i] - previous[i])
        }

        val score = (totalDifference.toFloat() / samples.size / 255f).coerceIn(0f, 1f)
        val activity = when {
            score < 0.012f -> "حركة منخفضة"
            score < 0.045f -> "حركة متوسطة"
            else -> "تغير بصري مرتفع"
        }
        return Result(score, activity, samples.size)
    }

    fun reset() {
        previousSamples = null
    }
}
