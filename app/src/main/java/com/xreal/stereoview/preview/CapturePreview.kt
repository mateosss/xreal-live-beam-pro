package com.xreal.stereoview.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.xreal.stereoview.R
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live capture view of the phone in portrait, in the style of a recording's preview: every
 * camera as streamed in its own tile (pinch to zoom, linked or per camera), with its size and
 * measured frame rate, and the live gyroscope and accelerometer curves under them.
 *
 * Frames and samples may arrive on any thread. Tiles show at most [MAX_TILE_FPS] frames per
 * second each; decoding (color JPEGs) happens on a worker, and a frame arriving while the
 * previous one is still being prepared is skipped, so the camera threads never wait.
 *
 * A tile created with [Tile.external] shows nothing itself: the stereo renderer is laid over it
 * ([cameraArea]), for cameras whose frames only reach the GPU (the phone's own).
 */
class CapturePreview(context: Context) : LinearLayout(context) {
    class Tile(val name: String, val external: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "CapturePreview").apply { priority = Thread.NORM_PRIORITY - 1 } }

    private class TileState(val spec: Tile, val image: ZoomImageView?, val caption: TextView) {
        val busy = AtomicBoolean(false)

        @Volatile var lastShownMs = 0L

        @Volatile var size = ""
        var frames = 0
        var fps = 0.0
        var countStartMs = 0L
    }

    private var tiles: List<TileState> = emptyList()
    private val zoom = ZoomGroup(PreviewPrefs.linkZoom(context))

    /** The area of the camera tiles (for laying the stereo renderer over external tiles). */
    val cameraArea: LinearLayout = LinearLayout(context).apply { orientation = VERTICAL }
    val gyro = LiveImuPlotView(context, "gyroscope", "rad/s")
    val accel = LiveImuPlotView(context, "accelerometer", "m/s²")
    private val imuTitle = TextView(context).apply {
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
    }
    private val zoomButton = ZoomLinkButton(context, zoom.linked) { on ->
        PreviewPrefs.setLinkZoom(context, on)
        zoom.setLinked(on, tiles.size)
        tiles.forEachIndexed { i, t -> t.image?.zoom = zoom.stateFor(i) }
    }
    private var cameraNote: TextView? = null

    private val bgPaint = android.graphics.Paint().apply { color = ContextCompat.getColor(context, R.color.home_background) }
    private val hole = android.graphics.Rect()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(cameraArea, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f))
        val imuRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(imuTitle, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(zoomButton)
        }
        addView(imuRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val plots = LinearLayout(context).apply { orientation = VERTICAL }
        plots.addView(gyro, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) })
        plots.addView(accel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(plots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * The background everywhere but over external tiles: the renderer's SurfaceView lies
     * behind the window there and shows only where nothing opaque is drawn.
     */
    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.save()
        for (t in tiles) {
            if (!t.spec.external) continue
            val tile = t.caption.parent as View
            tile.getDrawingRect(hole)
            offsetDescendantRectToMyCoords(tile, hole)
            canvas.clipOutRect(hole)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.restore()
    }

    /** Lays out one tile per camera: two columns, as the recording preview in portrait. */
    fun setTiles(specs: List<Tile>) {
        cameraArea.removeAllViews()
        zoom.reset()
        val columns = if (specs.size <= 1) 1 else 2
        val states = ArrayList<TileState>()
        specs.chunked(columns).forEach { rowSpecs ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (spec in rowSpecs) {
                val tile = FrameLayout(context).apply { if (!spec.external) setBackgroundColor(Color.BLACK) }
                val image = if (spec.external) null else ZoomImageView(context, zoom.stateFor(states.size))
                image?.let { tile.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
                val caption = TextView(context).apply {
                    text = spec.name
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(150, 0, 0, 0))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    typeface = Typeface.MONOSPACE
                }
                tile.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(tile, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                states += TileState(spec, image, caption)
            }
            repeat(columns - rowSpecs.size) { row.addView(View(context), LayoutParams(0, 1, 1f)) }
            cameraArea.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        cameraNote = null
        tiles = states
        invalidate()
        zoomButton.visibility = if (states.count { it.image != null } > 1) View.VISIBLE else View.GONE
    }

    /** A message across the camera area (e.g. "Connecting…"), or null to remove it. */
    fun setCameraMessage(message: String?) {
        cameraNote?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cameraNote = null
        if (message == null) return
        val note = TextView(context).apply {
            text = message
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 200, 150))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        (cameraArea.getChildAt(0) as? ViewGroup)?.let { row ->
            val first = row.getChildAt(0) as? FrameLayout ?: return
            first.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            cameraNote = note
        }
    }

    fun setImuTitle(text: String) {
        imuTitle.text = text
    }

    /** An 8-bit grayscale frame for tile [index], [width] × [height], row-major. */
    fun gray(index: Int, image: ByteArray, width: Int, height: Int) {
        val tile = accept(index) ?: return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image, 0, width * height))
        show(tile, bitmap, "$width×$height")
    }

    /** A JPEG frame for tile [index], decoded at half size or less (enough for a phone tile). */
    fun jpeg(index: Int, jpeg: ByteArray) {
        val tile = accept(index) ?: return
        worker.execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = if (bounds.outWidth > 960) 2 else 1 }
            val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull()
            if (bitmap == null) tile.busy.set(false) else show(tile, bitmap, "${bounds.outWidth}×${bounds.outHeight}")
        }
    }

    /** A raw NV12 color frame for tile [index], converted at half size or less. */
    fun nv12(index: Int, nv12: ByteArray, width: Int, height: Int) {
        val tile = accept(index) ?: return
        worker.execute {
            val step = if (width > 960) 2 else 1
            val bitmap = runCatching {
                Bitmap.createBitmap(com.xreal.stereoview.util.Nv12.toArgb(nv12, width, height, step), width / step, height / step, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            if (bitmap == null) tile.busy.set(false) else show(tile, bitmap, "$width×$height")
        }
    }

    /** Counts a frame of an external tile (drawn by the renderer), for its caption. */
    fun frame(index: Int, size: String) {
        val tile = tiles.getOrNull(index) ?: return
        tile.size = size
        main.post { count(tile) }
    }

    /** The tile, if a frame for it should be shown now (else null: skipped). */
    private fun accept(index: Int): TileState? {
        val tile = tiles.getOrNull(index) ?: return null
        if (tile.image == null) return null
        val now = SystemClock.uptimeMillis()
        if (now - tile.lastShownMs < 1000 / MAX_TILE_FPS) {
            main.post { count(tile) }
            return null
        }
        if (!tile.busy.compareAndSet(false, true)) {
            main.post { count(tile) }
            return null
        }
        tile.lastShownMs = now
        return tile
    }

    private fun show(tile: TileState, bitmap: Bitmap, size: String) {
        tile.size = size
        main.post {
            tile.image?.bitmap = bitmap
            tile.busy.set(false)
            count(tile)
        }
    }

    /** Main thread: one more frame of [tile]; its caption shows the rate once a second. */
    private fun count(tile: TileState) {
        val now = SystemClock.uptimeMillis()
        if (tile.countStartMs == 0L) tile.countStartMs = now
        tile.frames++
        if (now - tile.countStartMs >= 1000) {
            tile.fps = tile.frames * 1000.0 / (now - tile.countStartMs)
            tile.frames = 0
            tile.countStartMs = now
            tile.caption.text = listOf(tile.spec.name, tile.size, "%.1f fps".format(tile.fps)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /** Clears images and curves (the cameras stopped or changed). */
    fun clear() {
        tiles.forEach {
            it.image?.bitmap = null
            it.caption.text = it.spec.name
            it.frames = 0
            it.countStartMs = 0
        }
        gyro.clear()
        accel.clear()
    }

    fun release() {
        worker.shutdownNow()
    }

    companion object {
        /** Frames per second each tile shows at most (the cameras' own rates are in the captions). */
        const val MAX_TILE_FPS = 30
    }
}
