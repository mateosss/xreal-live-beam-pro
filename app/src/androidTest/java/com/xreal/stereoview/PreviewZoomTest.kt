package com.xreal.stereoview

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xreal.stereoview.preview.ImuPlotView
import com.xreal.stereoview.preview.ZoomImageView
import com.xreal.stereoview.preview.ZoomState
import com.xreal.stereoview.record.Dataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Gestures of the recording preview, sent straight to the views. */
@RunWith(AndroidJUnit4::class)
class PreviewZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun View.size(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun event(down: Long, t: Long, action: Int, vararg points: Pair<Float, Float>): MotionEvent {
        val props = Array(points.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { MotionEvent.PointerCoords().apply { x = points[it].first; y = points[it].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(down, t, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** Two fingers from [from] apart to [to] apart along the x (or y) axis around the view center. */
    private fun pinch(v: View, from: Float, to: Float, horizontal: Boolean) {
        val cx = v.width / 2f
        val cy = v.height / 2f
        fun pts(d: Float) = if (horizontal) arrayOf(cx - d / 2 to cy, cx + d / 2 to cy) else arrayOf(cx to cy - d / 2, cx to cy + d / 2)
        val down = SystemClock.uptimeMillis()
        var t = down
        v.dispatchTouchEvent(event(down, t, MotionEvent.ACTION_DOWN, pts(from)[0]))
        v.dispatchTouchEvent(event(down, t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), *pts(from)))
        for (k in 1..10) {
            t += 16
            v.dispatchTouchEvent(event(down, t, MotionEvent.ACTION_MOVE, *pts(from + (to - from) * k / 10)))
        }
        v.dispatchTouchEvent(event(down, t, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), *pts(to)))
        v.dispatchTouchEvent(event(down, t, MotionEvent.ACTION_UP, pts(to)[0]))
    }

    @Test
    fun imagesZoomAndPanTogether() = instrumentation.runOnMainSync {
        val zoom = ZoomState()
        val a = ZoomImageView(context, zoom).apply { size(800, 600); bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888) }
        pinch(a, 100f, 400f, horizontal = true)
        assertTrue("pinch zooms in: ${zoom.scale}", zoom.scale > 3f)
        val u = zoom.u
        // Pan with one finger.
        val down = SystemClock.uptimeMillis()
        a.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, 400f to 300f))
        for (k in 1..5) a.dispatchTouchEvent(event(down, down + k * 16L, MotionEvent.ACTION_MOVE, 400f + k * 30f to 300f))
        a.dispatchTouchEvent(event(down, down + 100, MotionEvent.ACTION_UP, 550f to 300f))
        assertTrue("dragging right moves the view left in the image: ${zoom.u} vs $u", zoom.u < u)
        // Double tap: back to the whole image.
        val t = SystemClock.uptimeMillis()
        for (d in listOf(0L, 120L)) {
            a.dispatchTouchEvent(event(t + d, t + d, MotionEvent.ACTION_DOWN, 300f to 300f))
            a.dispatchTouchEvent(event(t + d, t + d + 30, MotionEvent.ACTION_UP, 300f to 300f))
        }
        assertEquals(1f, zoom.scale)
    }

    @Test
    fun imuPlotsZoomTimeAndValues() = instrumentation.runOnMainSync {
        val n = 2000
        val imu = Dataset.Imu(LongArray(n) { it * 5_000_000L }, Array(6) { c -> FloatArray(n) { i -> c + (i % 50) / 50f } })
        var window = 0L
        val p = ImuPlotView(context, "gyroscope", "rad/s", 0..2).apply {
            this.imu = imu
            cursorNs = 5_000_000_000L
            onWindowChange = { window = it; windowNs = it }
            size(1080, 350)
        }
        pinch(p, 150f, 600f, horizontal = true)
        assertTrue("horizontal pinch out shortens the span: $window", window in 1 until 2_000_000_000L)
        assertEquals(1f, p.valueZoom)
        pinch(p, 80f, 300f, horizontal = false)
        assertTrue("vertical pinch zooms the values: ${p.valueZoom}", p.valueZoom > 3f)
        assertEquals("a vertical pinch leaves the span", window, p.windowNs)
        // Drawing at close zoom (dots) must work.
        p.draw(android.graphics.Canvas(Bitmap.createBitmap(1080, 350, Bitmap.Config.ARGB_8888)))
    }
}
