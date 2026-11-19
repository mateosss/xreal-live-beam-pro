package com.xreal.stereoview.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The last [windowNs] of one live three-axis sensor (gyroscope or accelerometer), newest at the
 * right edge, in the style of [ImuPlotView]. [add] may be called from any thread; the view
 * redraws at most at the display's rate.
 */
class LiveImuPlotView(context: Context, private val title: String, private val unit: String) : View(context) {
    var windowNs = 5_000_000_000L

    /** Shown instead of curves when there is no data (e.g. "IMU off"). */
    var emptyText = "waiting for samples…"
        set(value) {
            field = value
            postInvalidate()
        }

    private val capacity = 8192
    private val t = LongArray(capacity)
    private val v = Array(3) { FloatArray(capacity) }
    private var head = 0
    private var size = 0
    private var rateCount = 0
    private var rateStartNs = 0L
    private var rateHz = 0.0
    @Volatile private var redrawPosted = false

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density
    private val colors = intArrayOf(Color.rgb(0xEF, 0x53, 0x50), Color.rgb(0x66, 0xBB, 0x6A), Color.rgb(0x42, 0xA5, 0xF5))
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.3f) }
    private val axis = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = dp(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = dp(11f) }
    private val bg = Paint().apply { color = Color.argb(255, 0x16, 0x1C, 0x21) }
    private val path = Path()

    fun add(timestampNs: Long, x: Float, y: Float, z: Float) {
        synchronized(this) {
            if (size > 0 && timestampNs <= t[(head - 1 + capacity) % capacity]) {
                if (timestampNs < t[(head - 1 + capacity) % capacity] - 1_000_000_000L) clear() else return
            }
            t[head] = timestampNs
            v[0][head] = x
            v[1][head] = y
            v[2][head] = z
            head = (head + 1) % capacity
            if (size < capacity) size++
            if (rateCount == 0) rateStartNs = timestampNs
            rateCount++
            if (timestampNs - rateStartNs >= 1_000_000_000L) {
                rateHz = (rateCount - 1) * 1e9 / (timestampNs - rateStartNs)
                rateCount = 0
            }
        }
        if (!redrawPosted) {
            redrawPosted = true
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        synchronized(this) {
            size = 0
            head = 0
            rateCount = 0
            rateHz = 0.0
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        redrawPosted = false
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val pad = dp(6f)
        val top = dp(18f)
        val w = width - 2 * pad
        val h = height - top - pad
        synchronized(this) {
            val header = if (size == 0) "$title ($unit)" else "$title ($unit) · %.0f Hz".format(rateHz)
            canvas.drawText(header, pad, dp(13f), text)
            if (size == 0 || w <= 0 || h <= 0) {
                canvas.drawText(emptyText, pad, top + h / 2, text)
                return
            }
            val newest = t[(head - 1 + capacity) % capacity]
            val oldest = newest - windowNs
            // Visible samples: walk back from the newest.
            var n = 0
            while (n < size && t[(head - 1 - n + 2 * capacity) % capacity] >= oldest) n++
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (k in 0 until n) {
                val i = (head - 1 - k + 2 * capacity) % capacity
                for (c in 0..2) {
                    lo = minOf(lo, v[c][i])
                    hi = maxOf(hi, v[c][i])
                }
            }
            if (hi - lo < 1e-3f) {
                hi += 0.5f
                lo -= 0.5f
            }
            val margin = (hi - lo) * 0.08f
            lo -= margin
            hi += margin
            fun y(value: Float) = top + h * (1 - (value - lo) / (hi - lo))
            if (lo < 0 && hi > 0) canvas.drawLine(pad, y(0f), pad + w, y(0f), axis)
            // About one point per pixel column (a live view; the recording keeps every sample).
            val stride = (n / w.toInt().coerceAtLeast(1)).coerceAtLeast(1)
            for (c in 0..2) {
                path.reset()
                var k = n - 1
                var first = true
                while (k >= 0) {
                    val i = (head - 1 - k + 2 * capacity) % capacity
                    val x = pad + w * ((t[i] - oldest).toFloat() / windowNs)
                    if (first) path.moveTo(x, y(v[c][i])) else path.lineTo(x, y(v[c][i]))
                    first = false
                    k -= if (k in 1 until stride) k else stride
                }
                line.color = colors[c]
                canvas.drawPath(path, line)
            }
            val last = (head - 1 + capacity) % capacity
            val legend = "x %+.2f  y %+.2f  z %+.2f".format(v[0][last], v[1][last], v[2][last])
            canvas.drawText(legend, width - pad - text.measureText(legend), dp(13f), text)
            canvas.drawText("%.0f s".format(windowNs / 1e9), pad, top + h - dp(2f), text)
        }
    }
}
