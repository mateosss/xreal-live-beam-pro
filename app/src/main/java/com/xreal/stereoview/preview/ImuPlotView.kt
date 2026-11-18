package com.xreal.stereoview.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.xreal.stereoview.record.Dataset
import kotlin.math.abs
import kotlin.math.max

/**
 * Three channels of imu0 (x, y, z of the gyroscope or the accelerometer) in a time window
 * centered on the cursor, which stays in the middle. Each pixel column draws the min–max of
 * its samples, so long windows stay fast and spikes stay visible. The legend shows the values
 * at the cursor. Dragging horizontally scrubs like the timeline. Pinching zooms around the
 * cursor: horizontally the time span (reported through [onWindowChange], so plots stay in step),
 * vertically the value axis around the values at the cursor; double-tap returns the value axis to
 * auto-fit. Zoomed in far enough, each sample is drawn as a dot. Times are dataset ns.
 */
class ImuPlotView(context: Context, private val title: String, private val unit: String, private val channels: IntRange) : View(context) {
    var imu: Dataset.Imu? = null
        set(value) {
            field = value
            invalidate()
        }
    var cursorNs = 0L
        set(value) {
            field = value
            invalidate()
        }
    /** Visible span in ns. */
    var windowNs = 5_000_000_000L
        set(value) {
            field = value
            invalidate()
        }
    /** Called while dragging, with the new cursor time. */
    var onScrub: ((Long) -> Unit)? = null
    /** Called while pinching horizontally, with the new span. */
    var onWindowChange: ((Long) -> Unit)? = null
    /** Value-axis zoom: 1 fits the visible samples, larger magnifies around the cursor values. */
    var valueZoom = 1f
        set(value) {
            field = value.coerceIn(1f, 1000f)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val colors = intArrayOf(Color.rgb(0xEF, 0x53, 0x50), Color.rgb(0x66, 0xBB, 0x6A), Color.rgb(0x42, 0xA5, 0xF5))
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.3f) }
    private val axis = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = dp(1f) }
    private val cursor = Paint().apply { color = Color.argb(200, 255, 255, 255); strokeWidth = dp(1.5f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = dp(11f) }
    private val bg = Paint().apply { color = Color.argb(255, 0x16, 0x1C, 0x21) }
    private val path = Path()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun formatSpan(ns: Long): String = when {
        ns >= 10_000_000_000L -> "%.0f s".format(ns / 1e9)
        ns >= 1_000_000_000L -> "%.1f s".format(ns / 1e9)
        else -> "%.0f ms".format(ns / 1e6)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(8f), dp(8f), bg)
        val imu = imu
        val top = dp(20f)
        val bottom = height - dp(6f)
        val left = dp(6f)
        val right = width - dp(6f)
        canvas.drawText(title, left + dp(4f), dp(14f), text)
        if (imu == null || imu.t.isEmpty()) {
            canvas.drawText("no imu0 in this recording", left + dp(4f), height / 2f, text)
            return
        }
        val from = cursorNs - windowNs / 2
        val to = cursorNs + windowNs / 2
        val i0 = imu.indexAt(from).coerceAtLeast(0)
        val i1 = (imu.indexAt(to) + 1).coerceAtMost(imu.t.size - 1)
        // Scale: symmetric around zero for the gyroscope-like channels would hide offsets, so
        // fit the visible min and max with a margin.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (c in channels) for (i in i0..i1) {
            val v = imu.values[c][i]
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (lo > hi) { lo = -1f; hi = 1f }
        var margin = max((hi - lo) * 0.1f, 1e-3f)
        lo -= margin
        hi += margin
        if (valueZoom > 1f) {
            // Around the values at the cursor.
            val at = imu.indexAt(cursorNs)
            val here = channels.map { imu.values[it][at] }.filter { !it.isNaN() }
            val center = if (here.isEmpty()) (lo + hi) / 2 else (here.min() + here.max()) / 2
            val half = (hi - lo) / 2 / valueZoom
            lo = center - half
            hi = center + half
            margin = 0f
        }
        fun yOf(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        fun xOf(t: Long) = left + (right - left) * ((t - from).toDouble() / windowNs).toFloat()
        if (lo < 0 && hi > 0) canvas.drawLine(left, yOf(0f), right, yOf(0f), axis)
        canvas.drawText("%.2f".format(hi - margin), right - dp(40f), top + dp(10f), text)
        canvas.drawText("%.2f".format(lo + margin), right - dp(40f), bottom - dp(2f), text)

        val columns = (right - left).toInt().coerceAtLeast(1)
        // Few samples on screen: draw them as dots joined by lines; else min–max per pixel column.
        val sparse = i1 - i0 < columns / 4
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for ((k, c) in channels.withIndex()) {
            line.color = colors[k % colors.size]
            path.reset()
            var started = false
            if (sparse) {
                dot.color = line.color
                for (i in i0..i1) {
                    val v = imu.values[c][i]
                    if (v.isNaN()) continue
                    val x = xOf(imu.t[i])
                    val y = yOf(v)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    canvas.drawCircle(x, y, dp(2.2f), dot)
                }
                canvas.drawPath(path, line)
                continue
            }
            var i = i0
            for (col in 0 until columns) {
                val colEnd = from + windowNs * (col + 1) / columns
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                while (i <= i1 && imu.t[i] < colEnd) {
                    val v = imu.values[c][i]
                    if (!v.isNaN()) { if (v < mn) mn = v; if (v > mx) mx = v }
                    i++
                }
                if (mn > mx) continue
                val x = left + col
                if (!started) { path.moveTo(x, yOf(mn)); started = true } else path.lineTo(x, yOf(mn))
                if (mx != mn) path.lineTo(x, yOf(mx))
            }
            canvas.drawPath(path, line)
        }
        canvas.restore()
        val spanLabel = buildString {
            append(formatSpan(windowNs))
            if (valueZoom > 1f) append("  ·  values ×%.1f".format(valueZoom))
        }
        canvas.drawText(spanLabel, left + dp(4f), bottom - dp(2f), text)
        val cx = xOf(cursorNs)
        canvas.drawLine(cx, top, cx, bottom, cursor)

        // Legend: values at the cursor.
        val at = imu.indexAt(cursorNs)
        var lx = left + text.measureText(title) + dp(16f)
        for ((k, c) in channels.withIndex()) {
            text.color = colors[k % colors.size]
            val label = "${"xyz"[k]} %+.3f".format(imu.values[c][at])
            canvas.drawText(label, lx, dp(14f), text)
            lx += text.measureText(label) + dp(10f)
        }
        text.color = Color.argb(200, 255, 255, 255)
        canvas.drawText(unit, lx, dp(14f), text)
    }

    private var lastX = 0f
    /** Set once a second finger touches: no scrubbing until all fingers are up. */
    private var multiTouch = false

    private val pinch = Pinch(dp(24f)) { _, sx, sy, _, _ ->
        if (sx != 1f) {
            val w = (windowNs / sx).toLong().coerceIn(MIN_WINDOW_NS, MAX_WINDOW_NS)
            if (w != windowNs) onWindowChange?.invoke(w) ?: run { windowNs = w }
        }
        if (sy != 1f) valueZoom *= sy
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            valueZoom = 1f
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                multiTouch = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch || pinch.active) return true
                val dx = e.x - lastX
                if (abs(dx) > 0) {
                    lastX = e.x
                    // Drag the curves: moving right goes back in time.
                    onScrub?.invoke(cursorNs - (dx / width * windowNs).toLong())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    companion object {
        const val MIN_WINDOW_NS = 2_000_000L
        const val MAX_WINDOW_NS = 120_000_000_000L
    }
}
