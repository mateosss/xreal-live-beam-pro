package com.xreal.stereoview.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The bitrate slider's logarithmic scale (positions 0..1 for [MIN]..[MAX] Mbit/s), its magnetic
 * [POINTS], and how bitrates are written and typed.
 */
object BitrateScale {
    const val MIN = 0.1f
    const val MAX = 100f

    /** What a typed bitrate may be (the slider covers [MIN]..[MAX]). */
    const val TYPED_MIN = 0.05f
    const val TYPED_MAX = 500f

    /** Where the slider snaps to, Mbit/s. */
    val POINTS = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 12f, 20f, 40f)

    /** How close (in slider position) a point pulls the thumb onto itself. */
    const val SNAP = 0.025f

    private val span = log10(MAX / MIN)

    fun toPosition(mbps: Float): Float = (log10(mbps.coerceIn(MIN, MAX) / MIN) / span).coerceIn(0f, 1f)

    fun fromPosition(position: Float): Float = MIN * 10f.pow(position.coerceIn(0f, 1f) * span)

    /** The point within [SNAP] of [position], if any. */
    fun magnet(position: Float): Float? =
        POINTS.minByOrNull { abs(toPosition(it) - position) }?.takeIf { abs(toPosition(it) - position) <= SNAP }

    /** The bitrate at slider [position]: a magnetic point near it, else rounded to two significant digits. */
    fun atPosition(position: Float): Float = magnet(position) ?: roundSignificant(fromPosition(position))

    fun roundSignificant(v: Float, digits: Int = 2): Float {
        if (v <= 0f) return v
        val scale = 10.0.pow(digits - 1 - floor(log10(v.toDouble())))
        return ((v * scale).roundToInt() / scale).toFloat()
    }

    /** "250 kbit/s", "2.5 Mbit/s", "40 Mbit/s". */
    fun format(mbps: Float): String =
        if (mbps < 1f) "${number(mbps * 1000)} kbit/s" else "${number(mbps)} Mbit/s"

    /** [mbps] as typed in the field (Mbit/s): "0.25", "4", "2.5". */
    fun number(v: Float): String {
        val r = roundSignificant(v, 3)
        return if (r == r.roundToInt().toFloat()) r.roundToInt().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /**
     * A typed bitrate in Mbit/s: a number is Mbit/s, unless followed by k (kbit/s), e.g. "2.5",
     * "250k", "250 kbps", "4 Mbit/s", "0,5". Null if unreadable; clamped to [TYPED_MIN]..[TYPED_MAX].
     */
    fun parse(text: String): Float? {
        val t = text.trim().lowercase().replace(',', '.')
        val match = Regex("""^(\d+(?:\.\d*)?|\.\d+)\s*([km]?)(?:bit/s|bps|b/s|b)?$""").find(t) ?: return null
        val n = match.groupValues[1].toFloatOrNull() ?: return null
        val mbps = if (match.groupValues[2] == "k") n / 1000f else n
        if (mbps <= 0f) return null
        return mbps.coerceIn(TYPED_MIN, TYPED_MAX)
    }
}
