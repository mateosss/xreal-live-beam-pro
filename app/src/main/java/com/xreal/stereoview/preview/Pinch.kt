package com.xreal.stereoview.preview

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Two-finger pinch tracking without the platform's large minimum finger distance (27 mm in
 * ScaleGestureDetector, more than a plot is tall on a phone). Reports, per move, how much the
 * fingers' distance changed overall and along each axis, and the point between them. Axis
 * factors are 1 while the fingers are closer than [minAxisPx] along that axis.
 */
class Pinch(private val minAxisPx: Float, private val onPinch: (scale: Float, scaleX: Float, scaleY: Float, focusX: Float, focusY: Float) -> Unit) {
    var active = false
        private set
    private var lastDx = 0f
    private var lastDy = 0f

    /** Feed every touch event; returns true while a pinch is in progress. */
    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) start(e)
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue with the remaining two fingers, if any.
                active = false
                if (e.pointerCount > 2) {
                    val skip = e.actionIndex
                    val idx = (0 until e.pointerCount).filter { it != skip }.take(2)
                    lastDx = abs(e.getX(idx[1]) - e.getX(idx[0]))
                    lastDy = abs(e.getY(idx[1]) - e.getY(idx[0]))
                    active = true
                }
            }
            MotionEvent.ACTION_MOVE -> if (active && e.pointerCount >= 2) {
                val dx = abs(e.getX(1) - e.getX(0))
                val dy = abs(e.getY(1) - e.getY(0))
                val sx = if (dx >= minAxisPx && lastDx >= minAxisPx) dx / lastDx else 1f
                val sy = if (dy >= minAxisPx && lastDy >= minAxisPx) dy / lastDy else 1f
                val d0 = hypot(lastDx, lastDy)
                val d1 = hypot(dx, dy)
                val s = if (d0 > 1f && d1 > 1f) d1 / d0 else 1f
                lastDx = dx
                lastDy = dy
                onPinch(s, sx, sy, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = false
        }
        return active
    }

    private fun start(e: MotionEvent) {
        lastDx = abs(e.getX(1) - e.getX(0))
        lastDy = abs(e.getY(1) - e.getY(0))
        active = true
    }
}
