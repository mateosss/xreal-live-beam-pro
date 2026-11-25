package com.xreal.stereoview.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.xreal.stereoview.R

/** A slider that draws a dot at each of [marks] (values in its range): e.g. the points it snaps to. */
class MarkedSlider(context: Context, attrs: AttributeSet? = null) : Slider(context, attrs) {
    var marks: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.panel_text_secondary) }
    private val radius = 2.5f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = valueTo - valueFrom
        if (range <= 0f) return
        val y = height / 2f
        for (m in marks) {
            val x = trackSidePadding + (m - valueFrom) / range * trackWidth
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
