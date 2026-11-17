package com.xreal.stereoview.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The recording's time axis: drag anywhere to move the cursor. Below the axis, one lane per
 * sensor marks where samples are missing. In trim mode two handles bound the kept range (the
 * rest is dimmed); dragging near a handle moves it instead of the cursor.
 * Times are in ns relative to the recording's start.
 */
class TimelineView(context: Context) : View(context) {
    interface Listener {
        fun onSeek(timeNs: Long, fromUser: Boolean)
        fun onTrimChanged(startNs: Long, endNs: Long)
    }

    /** A row of gap markers: where a sensor's samples are missing. */
    class Lane(val label: String, val color: Int, val gapsNs: LongArray)

    var listener: Listener? = null
    var durationNs: Long = 1
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }
    var positionNs: Long = 0
        private set
    var lanes: List<Lane> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    var trimMode = false
        set(value) {
            field = value
            invalidate()
        }
    var trimStartNs = 0L
        private set
    var trimEndNs = 0L
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val pad = dp(22f)
    private val axisY get() = dp(18f)
    private val laneHeight = dp(7f)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255); strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val played = Paint(track).apply { color = ACCENT }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255); strokeWidth = dp(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = dp(10f) }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2f) }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRIM }
    private val marker = Paint().apply { strokeWidth = dp(1.5f) }
    private val laneText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(7f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = axisY + dp(14f) + lanes.size * laneHeight + dp(16f)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h.toInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Dragging the cursor or trim handles near the screen edges must not trigger Back.
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, width, height))
    }

    fun setPosition(ns: Long) {
        positionNs = ns.coerceIn(0, durationNs)
        invalidate()
    }

    fun setTrim(startNs: Long, endNs: Long) {
        trimStartNs = startNs.coerceIn(0, durationNs)
        trimEndNs = endNs.coerceIn(trimStartNs, durationNs)
        invalidate()
    }

    private fun x(ns: Long) = pad + (width - 2 * pad) * (ns.toDouble() / durationNs).toFloat()
    private fun ns(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * durationNs.toDouble()).toLong()

    override fun onDraw(canvas: Canvas) {
        val y = axisY
        // Ticks every 1, 2 or 5 × 10^k seconds, about every 80 dp.
        val seconds = durationNs / 1e9
        val target = seconds / ((width - 2 * pad) / dp(80f)).coerceAtLeast(1f)
        val base = 10.0.pow(floor(log10(target.coerceAtLeast(1e-3))))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.first { it >= target }
        var t = 0.0
        while (t <= seconds + 1e-9) {
            val tx = x((t * 1e9).toLong())
            canvas.drawLine(tx, y + dp(4f), tx, y + dp(8f), tick)
            val label = if (step < 1) "%.1f".format(t) else "%.0f".format(t)
            canvas.drawText(label + "s", tx - text.measureText(label) / 2, y + dp(19f), text)
            t += step
        }
        canvas.drawLine(x(0), y, x(durationNs), y, track)
        canvas.drawLine(x(0), y, x(positionNs), y, played)

        // Gap lanes.
        var laneY = y + dp(26f)
        for (lane in lanes) {
            marker.color = lane.color
            laneText.color = lane.color
            canvas.drawText(lane.label, width - pad + dp(1f), laneY + laneHeight - dp(1.5f), laneText)
            for (g in lane.gapsNs) {
                val gx = x(g)
                canvas.drawLine(gx, laneY, gx, laneY + laneHeight - dp(2f), marker)
            }
            laneY += laneHeight
        }

        if (trimMode) {
            val a = x(trimStartNs)
            val b = x(trimEndNs)
            canvas.drawRect(0f, 0f, a, height.toFloat(), dim)
            canvas.drawRect(b, 0f, width.toFloat(), height.toFloat(), dim)
            for (hx in listOf(a, b)) {
                canvas.drawRoundRect(RectF(hx - dp(3f), dp(2f), hx + dp(3f), height - dp(2f)), dp(3f), dp(3f), handle)
            }
        }
        val cx = x(positionNs)
        canvas.drawLine(cx, dp(2f), cx, height - dp(2f), cursor)
        canvas.drawCircle(cx, y, dp(7f), knob)
    }

    private enum class Drag { CURSOR, START, END }
    private var drag = Drag.CURSOR

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = Drag.CURSOR
                if (trimMode) {
                    val da = abs(e.x - x(trimStartNs))
                    val db = abs(e.x - x(trimEndNs))
                    val grab = dp(24f)
                    if (da < grab || db < grab) drag = if (da <= db) Drag.START else Drag.END
                }
                move(e.x)
            }
            MotionEvent.ACTION_MOVE -> move(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun move(px: Float) {
        val t = ns(px)
        when (drag) {
            Drag.CURSOR -> {
                setPosition(t)
                listener?.onSeek(positionNs, true)
            }
            Drag.START -> {
                setTrim(minOf(t, trimEndNs), trimEndNs)
                setPosition(trimStartNs)
                listener?.onSeek(positionNs, true)
                listener?.onTrimChanged(trimStartNs, trimEndNs)
            }
            Drag.END -> {
                setTrim(trimStartNs, maxOf(t, trimStartNs))
                setPosition(trimEndNs)
                listener?.onSeek(positionNs, true)
                listener?.onTrimChanged(trimStartNs, trimEndNs)
            }
        }
    }

    companion object {
        val ACCENT = Color.rgb(0x4F, 0xC3, 0xF7)
        val TRIM = Color.rgb(0xFF, 0xB7, 0x4D)
    }
}
