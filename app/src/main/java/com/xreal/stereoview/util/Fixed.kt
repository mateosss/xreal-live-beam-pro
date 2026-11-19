package com.xreal.stereoview.util

/**
 * Appends [v] with exactly [decimals] digits after the point (like "%.10f", Monado's IMU CSVs),
 * without String.format's cost: the IMU writers call this thousands of times a second.
 */
fun StringBuilder.appendFixed(v: Double, decimals: Int = 10): StringBuilder {
    if (v.isNaN() || v.isInfinite() || kotlin.math.abs(v) >= 1e8) return append(v)
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val n = Math.round(kotlin.math.abs(v) * scale)
    if (v < 0 && n != 0L) append('-')
    append(n / scale)
    if (decimals > 0) {
        append('.')
        val frac = (n % scale).toString()
        repeat(decimals - frac.length) { append('0') }
        append(frac)
    }
    return this
}

fun StringBuilder.appendFixed(v: Float, decimals: Int = 10) = appendFixed(v.toDouble(), decimals)
