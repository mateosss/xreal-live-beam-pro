package com.xreal.stereoview.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * Zoom and pan shared by every camera tile, in image-relative coordinates, so the cameras stay
 * aligned with each other (and with bitmaps of any resolution). [scale] 1 shows the whole image.
 */
class ZoomState {
    var scale = 1f
        private set
    /** Image point (0..1) shown at the view center. */
    var u = 0.5f
        private set
    var v = 0.5f
        private set
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun set(scale: Float, u: Float, v: Float) {
        this.scale = scale.coerceIn(1f, MAX_SCALE)
        this.u = if (this.scale == 1f) 0.5f else u
        this.v = if (this.scale == 1f) 0.5f else v
        listeners.toList().forEach { it() }
    }

    fun reset() = set(1f, 0.5f, 0.5f)

    fun copyFrom(other: ZoomState) = set(other.scale, other.u, other.v)

    companion object {
        const val MAX_SCALE = 64f
    }
}

/**
 * The zoom of a row of camera tiles, linked (one [shared] state) or per tile. Unlinking keeps
 * the current zoom in every tile; linking moves every tile to the first one's (cam0) zoom and pan.
 */
class ZoomGroup(linked: Boolean) {
    var linked = linked
        private set
    val shared = ZoomState()
    private val own = ArrayList<ZoomState>()
    private val listeners = ArrayList<() -> Unit>()

    /** Called on any zoom change, linked or not. */
    fun addListener(l: () -> Unit) {
        listeners += l
        shared.addListener(l)
        own.forEach { it.addListener(l) }
    }

    private fun own(i: Int): ZoomState {
        while (own.size <= i) own += ZoomState().also { s -> listeners.forEach { s.addListener(it) } }
        return own[i]
    }

    /** The state tile [i] follows now. */
    fun stateFor(i: Int): ZoomState = if (linked) shared else own(i)

    fun anyZoomed(count: Int) = if (linked) shared.scale > 1f else (0 until count).any { own(it).scale > 1f }

    /** Links or unlinks [count] tiles; then give each tile [stateFor] its index. */
    fun setLinked(on: Boolean, count: Int) {
        if (on == linked) return
        if (on) shared.copyFrom(own(0)) else for (i in 0 until count) own(i).copyFrom(shared)
        linked = on
    }

    fun reset() {
        shared.reset()
        own.forEach { it.reset() }
    }
}

/**
 * Shows a camera frame whole (fit) or zoomed: pinch to zoom around the fingers, drag to pan,
 * double-tap to zoom in ×4 there or back out. Pixels are drawn nearest-neighbour, so zoomed
 * images show the recorded pixels rather than a blur.
 */
class ZoomImageView(context: Context, initialZoom: ZoomState) : View(context) {
    /**
     * The zoom this tile follows; may be swapped (linking or unlinking tiles). The constructor
     * parameter has another name so the gesture handlers below, created in initializers, use
     * this property (the current state) rather than the state the view was created with.
     */
    var zoom: ZoomState = initialZoom
        set(value) {
            if (value === field) return
            if (isAttachedToWindow) {
                field.removeListener(onZoom)
                value.addListener(onZoom)
            }
            field = value
            invalidate()
        }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    // White: ALPHA_8 bitmaps (grayscale camera frames, drawn over black) take their color from the paint.
    private val paint = Paint().apply { color = Color.WHITE; isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.density
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val matrix = Matrix()
    private val onZoom = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        zoom.addListener(onZoom)
    }

    override fun onDetachedFromWindow() {
        zoom.removeListener(onZoom)
        super.onDetachedFromWindow()
    }

    /** Screen pixels per bitmap pixel at the current zoom. */
    private fun pixelScale(b: Bitmap): Float = minOf(width.toFloat() / b.width, height.toFloat() / b.height) * zoom.scale

    /** The view center's image point, kept so the image never leaves an edge uncovered. */
    private fun clamped(b: Bitmap, s: Float, u: Float, v: Float): Pair<Float, Float> {
        fun clamp(c: Float, viewSize: Int, imageSize: Float): Float {
            val half = viewSize / (2f * imageSize)
            return if (half >= 0.5f) 0.5f else c.coerceIn(half, 1f - half)
        }
        return clamp(u, width, b.width * s) to clamp(v, height, b.height * s)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled || width == 0 || height == 0) return
        val s = pixelScale(b)
        val (u, v) = clamped(b, s, zoom.u, zoom.v)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(width / 2f - u * b.width * s, height / 2f - v * b.height * s)
        canvas.drawBitmap(b, matrix, paint)
        if (zoom.scale > 1f) {
            val text = "×%.1f".format(zoom.scale)
            canvas.drawText(text, width - label.measureText(text) - 6f * resources.displayMetrics.density, height - 6f * resources.displayMetrics.density, label)
        }
    }

    /** Sets the zoom so that the image point under (fx, fy) stays there. */
    private fun zoomAround(newScale: Float, fx: Float, fy: Float) {
        val b = bitmap ?: return
        val s = pixelScale(b)
        val (u, v) = clamped(b, s, zoom.u, zoom.v)
        val pu = u + (fx - width / 2f) / (s * b.width)
        val pv = v + (fy - height / 2f) / (s * b.height)
        val target = newScale.coerceIn(1f, ZoomState.MAX_SCALE)
        val s2 = s / zoom.scale * target
        zoom.set(target, pu - (fx - width / 2f) / (s2 * b.width), pv - (fy - height / 2f) / (s2 * b.height))
    }

    private val pinch = Pinch(10f * resources.displayMetrics.density) { scale, _, _, fx, fy ->
        zoomAround(zoom.scale * scale, fx, fy)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val b = bitmap ?: return false
            if (zoom.scale <= 1f || pinch.active || e2.pointerCount > 1) return false
            val s = pixelScale(b)
            val (u, v) = clamped(b, s, zoom.u, zoom.v)
            val (nu, nv) = clamped(b, s, u + dx / (s * b.width), v + dy / (s * b.height))
            zoom.set(zoom.scale, nu, nv)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom.scale > 1f) zoom.reset() else zoomAround(4f, e.x, e.y)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Keep scrolling parents from taking pinches and pans.
        if (e.pointerCount > 1 || zoom.scale > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        pinch.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
