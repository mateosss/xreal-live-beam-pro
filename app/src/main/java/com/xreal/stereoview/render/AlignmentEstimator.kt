package com.xreal.stereoview.render

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures the vertical misalignment between the left and right views from image content.
 *
 * Mean-intensity row profiles barely change when content moves horizontally, so they mostly
 * ignore stereo disparity. To remove the rest (content sliding in and out of a strip), each
 * strip's horizontal disparity is first found from column profiles, which in turn ignore
 * vertical offsets, and the right strip is moved by it. Comparing a strip at the left edge with
 * one at the right edge gives both the vertical offset and the roll.
 */
object AlignmentEstimator {
    /** NCC peaks below this are treated as "no reliable match". */
    private const val MIN_SCORE = 0.6

    /**
     * Offsets in rows: positive means the right view's content sits lower than the left's.
     * [regionDistance] is the horizontal distance between the two strips' centers, in pixels.
     */
    data class Estimate(val offsetLeftStrip: Double, val offsetRightStrip: Double, val regionDistance: Double, val score: Double) {
        val meanOffset: Double get() = (offsetLeftStrip + offsetRightStrip) / 2
        /** Slope of the offset across the image (rows per column); positive = right view rotated clockwise. */
        val slope: Double get() = (offsetRightStrip - offsetLeftStrip) / regionDistance
    }

    /** [left] and [right] are row-major luminance images, row 0 at the top. */
    fun estimate(left: FloatArray, right: FloatArray, width: Int, height: Int, maxShift: Int, maxDisparity: Int = width / 8): Estimate? {
        val strip = width / 3
        val a = stripOffset(left, right, width, height, maxDisparity, strip, maxShift, maxDisparity) ?: return null
        val b = stripOffset(left, right, width, height, width - strip - maxDisparity, width - maxDisparity, maxShift, maxDisparity)
            ?: return null
        return Estimate(a.first, b.first, (width - strip - 2 * maxDisparity).toDouble(), min(a.second, b.second))
    }

    private fun stripOffset(
        left: FloatArray,
        right: FloatArray,
        width: Int,
        height: Int,
        x0: Int,
        x1: Int,
        maxShift: Int,
        maxDisparity: Int,
    ): Pair<Double, Double>? {
        // Horizontal disparity of this strip: left columns x0..x1 against right columns around them.
        val cl = derivative(columnProfile(left, width, height, x0, x1))
        val cr = derivative(columnProfile(right, width, height, x0 - maxDisparity, x1 + maxDisparity))
        val disparity = (0..2 * maxDisparity).maxByOrNull { ncc(cl, cr, it, minOverlap = cl.size) }?.minus(maxDisparity) ?: 0

        val pl = derivative(rowProfile(left, width, height, x0, x1))
        val pr = derivative(rowProfile(right, width, height, x0 + disparity, x1 + disparity))
        val scores = DoubleArray(2 * maxShift + 1) { ncc(pl, pr, it - maxShift) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (scores[best] < MIN_SCORE) return null
        // Parabolic sub-row refinement around the peak.
        var offset = (best - maxShift).toDouble()
        if (best in 1 until scores.size - 1) {
            val l = scores[best - 1]
            val c = scores[best]
            val r = scores[best + 1]
            val denominator = l - 2 * c + r
            if (denominator < 0) offset += 0.5 * (l - r) / denominator
        }
        return offset to scores[best]
    }

    private fun rowProfile(image: FloatArray, width: Int, height: Int, x0: Int, x1: Int) = DoubleArray(height) { y ->
        var sum = 0.0
        for (x in x0 until x1) sum += image[y * width + x]
        sum / (x1 - x0)
    }

    private fun columnProfile(image: FloatArray, width: Int, height: Int, x0: Int, x1: Int) = DoubleArray(x1 - x0) { i ->
        var sum = 0.0
        for (y in 0 until height) sum += image[y * width + x0 + i]
        sum / height
    }

    private fun derivative(p: DoubleArray) = DoubleArray(p.size - 1) { p[it + 1] - p[it] }

    /** Normalized cross-correlation of a[y] with b[y + shift] over their overlap. */
    private fun ncc(a: DoubleArray, b: DoubleArray, shift: Int, minOverlap: Int = a.size / 2): Double {
        val start = maxOf(0, -shift)
        val end = minOf(a.size, b.size - shift)
        val n = end - start
        if (n < minOverlap) return -1.0
        var ma = 0.0
        var mb = 0.0
        for (y in start until end) {
            ma += a[y]
            mb += b[y + shift]
        }
        ma /= n
        mb /= n
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (y in start until end) {
            val da = a[y] - ma
            val db = b[y + shift] - mb
            ab += da * db
            aa += da * da
            bb += db * db
        }
        val denominator = sqrt(aa * bb)
        return if (denominator > 1e-12) ab / denominator else -1.0
    }
}
