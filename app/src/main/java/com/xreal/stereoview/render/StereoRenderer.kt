package com.xreal.stereoview.render

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.camera.FrameSynchronizer
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.ui.RecordView
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.ui.SbsLayout
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.Mat3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan

/**
 * Composites the left and right camera textures into one side-by-side frame: the left half of
 * the surface is the left eye, the right half the right eye. Both eyes are rectified into a
 * shared frame by [StereoGeometry] and stabilized together by [Stabilizer].
 *
 * Listener callbacks are delivered on the main thread.
 */
class StereoRenderer(
    private val listener: Listener,
    private val stabilizer: Stabilizer?,
) : GLSurfaceView.Renderer {
    interface Listener {
        /** New camera targets (0 = left camera, 1 = right camera); called after every GL context creation. */
        fun onSurfacesReady(left: SurfaceTexture, right: SurfaceTexture)
        fun onRenderStats(stats: RenderStats)
    }

    /** The streams' camera models (same instance twice for a single camera) and the display rotation. */
    data class Cameras(val left: CameraModel, val right: CameraModel, val displayRotation: Int)

    data class RenderStats(
        val leftFps: Float,
        val rightFps: Float,
        val drawFps: Float,
        val drawMs: Float,
        val sync: FrameSynchronizer.Stats,
        val outputSize: Size,
        val view: String,
    )

    /** Alignment correction measured from the image, to add to the current settings. */
    data class AlignmentResult(val pitchDeg: Double, val rollDeg: Double, val samples: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestRender: () -> Unit = {}
    private var queueEvent: (Runnable) -> Unit = {}
    private val sync = FrameSynchronizer { requestRender() }

    @Volatile
    var settings = StereoSettings()
        set(value) {
            field = value
            sync.timeoutMs = value.syncThresholdMs.toLong()
            requestRender()
        }

    @Volatile
    var cameras: Cameras? = null
        set(value) {
            field = value
            requestRender()
        }

    /** With the glasses cameras as source: the newest left/right pair to show. */
    @Volatile
    var glassesFrame: GlassesDevice.StereoFrame? = null
        set(value) {
            field = value
            requestRender()
        }

    /** Description of the current rectification, for the Info dialog. */
    @Volatile
    var geometrySummary: String = "not streaming"
        private set

    // GL thread state
    private var shader: StereoShader? = null
    private var grayShader: GrayShader? = null
    private var shownGlassesFrame: GlassesDevice.StereoFrame? = null
    private val textureIds = IntArray(2)
    private var surfaceTextures: List<SurfaceTexture> = emptyList()
    private val stMatrices = Array(2) { FloatArray(16).also { Matrix.setIdentityM(it, 0) } }
    private var width = 1
    private var height = 1
    private var geometry: StereoGeometry? = null
    private var geometryKey: Any? = null
    private var viewDescription = ""

    // Recording: set from the main thread, the GL side is created and released on the GL thread.
    @Volatile
    private var recording: Recording? = null
    private var glRecorder: GlRecorder? = null

    // Auto-alignment, GL thread
    @Volatile
    private var alignmentCallback: ((AlignmentResult?) -> Unit)? = null
    private val alignmentPitch = mutableListOf<Double>()
    private val alignmentRoll = mutableListOf<Double>()
    private var alignmentAttempts = 0
    private var alignmentFramebuffer = 0
    private var alignmentTexture = 0
    private val alignmentPixels: ByteBuffer =
        ByteBuffer.allocateDirect(ALIGN_WIDTH * ALIGN_HEIGHT * 4).order(ByteOrder.nativeOrder())

    // Stats, GL thread only
    private val newFrames = IntArray(2)
    private var draws = 0
    private var drawNanos = 0L
    private var statsStart = SystemClock.elapsedRealtime()

    /** Binds the renderer to its view; draws are only requested when frames arrive. */
    fun attach(view: GLSurfaceView) {
        requestRender = view::requestRender
        queueEvent = view::queueEvent
    }

    /** Starts storing every new camera frame into [recording]'s outputs. */
    fun startRecording(recording: Recording) {
        this.recording = recording
    }

    /** Stops storing frames and releases the encoder surfaces on the GL thread, then runs [onReleased] there. */
    fun stopRecording(onReleased: () -> Unit) {
        queueEvent(Runnable {
            recording = null
            releaseRecorder()
            onReleased()
        })
        requestRender()
    }

    private fun releaseRecorder() {
        try {
            glRecorder?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Releasing recording surfaces failed", e)
        }
        glRecorder = null
    }

    /**
     * Measures the vertical offset and roll between the eyes over the next frames.
     * [onResult] runs on the main thread, with null if the image had too little structure.
     */
    fun requestAlignment(onResult: (AlignmentResult?) -> Unit) {
        alignmentCallback = onResult
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context invalidates any previous textures; replace the SurfaceTextures too.
        surfaceTextures.forEach { it.release() }
        glRecorder = null // belonged to the previous context
        shader = StereoShader()
        grayShader = GrayShader(2)
        shownGlassesFrame = null
        alignmentFramebuffer = 0
        surfaceTextures = List(2) { eye ->
            textureIds[eye] = StereoShader.createExternalTexture()
            SurfaceTexture(textureIds[eye]).apply {
                setOnFrameAvailableListener({ sync.onFrameAvailable(eye) }, sync.handler)
            }
        }
        val (left, right) = surfaceTextures
        mainHandler.post { listener.onSurfacesReady(left, right) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val start = SystemClock.elapsedRealtimeNanos()
        if (settings.cameraSource == com.xreal.stereoview.ui.CameraSource.GLASSES) {
            drawGlasses()
            draws++
            drawNanos += SystemClock.elapsedRealtimeNanos() - start
            maybeReportStats()
            return
        }
        val fresh = sync.consume()
        for (eye in 0..1) {
            if (!fresh[eye]) continue
            try {
                surfaceTextures[eye].updateTexImage()
                surfaceTextures[eye].getTransformMatrix(stMatrices[eye])
                newFrames[eye]++
            } catch (e: RuntimeException) {
                Log.w(TAG, "updateTexImage failed", e)
            }
        }
        if (fresh[0] && fresh[1]) {
            val deltaNs = abs(surfaceTextures[0].timestamp - surfaceTextures[1].timestamp)
            sync.recordPair(deltaNs / 1e6)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val cams = cameras
        val shader = shader
        if (cams != null && shader != null) {
            val s = settings
            val geometry = geometryFor(cams, s)
            val models = listOf(cams.left, cams.right)
            val eyeWidth = width / 2
            val halfSbs = when (s.sbsLayout) {
                SbsLayout.FULL -> false
                SbsLayout.HALF -> true
                SbsLayout.AUTO -> width.toFloat() / height < 3f
            }
            val eyeAspect = eyeWidth.toDouble() * (if (halfSbs) 2 else 1) / height
            val (tanH, tanV) = geometry.displayTangents(s.scaleMode, eyeAspect, s.glassesFovDeg.toDouble())
            val zoom = s.depth.coerceIn(StereoSettings.DEPTH_MIN, StereoSettings.DEPTH_MAX).toDouble()
            val deviceCorrection = stabilizer?.correction(surfaceTextures[0].timestamp, s.stabilization.timeConstantSec)
                ?: Mat3.identity()
            val stabilization = geometry.toRectFrame(deviceCorrection)
            viewDescription = "eye %.1f°×%.1f° %s, %s".format(
                Math.toDegrees(2 * atan(tanH / zoom)), Math.toDegrees(2 * atan(tanV / zoom)),
                s.scaleMode.label, if (halfSbs) "half SBS" else "full SBS",
            )

            recording?.let { record(it, shader, geometry, models, s, fresh, stabilization) }

            if (alignmentCallback != null && fresh[0] && fresh[1]) {
                measureAlignment(shader, geometry, models, tanH, tanV, zoom, s, stabilization)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }

            for (viewport in 0..1) {
                val camera = if (s.swapEyes) 1 - viewport else viewport
                // Positive convergence moves the left image left and the right image right.
                val shift = (if (viewport == 0) 1.0 else -1.0) * s.convergencePx / eyeWidth
                val ray = geometry.rayMatrix(
                    camera, tanH, tanV, shift, zoom,
                    s.alignPitchDeg.toDouble(), s.alignRollDeg.toDouble(), stabilization,
                )
                GLES20.glViewport(viewport * eyeWidth, 0, eyeWidth, height)
                shader.draw(textureIds[camera], ray, models[camera], stMatrices[camera])
            }
        }

        draws++
        drawNanos += SystemClock.elapsedRealtimeNanos() - start
        maybeReportStats()
    }

    /**
     * Shows the glasses' left and right tracking-camera images in the two eyes, fitted
     * according to the scale mode (1:1 behaves like Fit, as their angles are unknown here).
     */
    private fun drawGlasses() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val gray = grayShader ?: return
        val frame = glassesFrame ?: return
        if (frame !== shownGlassesFrame) {
            gray.upload(0, frame.left.image, IMAGE_W, IMAGE_H)
            gray.upload(1, frame.right.image, IMAGE_W, IMAGE_H)
            shownGlassesFrame = frame
            newFrames[0]++
            newFrames[1]++
        }
        val s = settings
        val eyeWidth = width / 2
        val halfSbs = when (s.sbsLayout) {
            SbsLayout.FULL -> false
            SbsLayout.HALF -> true
            SbsLayout.AUTO -> width.toFloat() / height < 3f
        }
        val eyeAspect = eyeWidth.toFloat() * (if (halfSbs) 2 else 1) / height
        val imageAspect = IMAGE_W.toFloat() / IMAGE_H
        val zoom = s.depth.coerceIn(StereoSettings.DEPTH_MIN, StereoSettings.DEPTH_MAX)
        val (sx, sy) = when (s.scaleMode) {
            ScaleMode.STRETCH -> 1f to 1f
            ScaleMode.FILL -> if (imageAspect > eyeAspect) imageAspect / eyeAspect to 1f else 1f to eyeAspect / imageAspect
            ScaleMode.FIT, ScaleMode.REAL -> if (imageAspect > eyeAspect) 1f to eyeAspect / imageAspect else imageAspect / eyeAspect to 1f
        }
        viewDescription = "glasses cameras ${IMAGE_W}×$IMAGE_H, ${s.scaleMode.label}, ${if (halfSbs) "half SBS" else "full SBS"}"
        for (viewport in 0..1) {
            val slot = if (s.swapEyes) 1 - viewport else viewport
            GLES20.glViewport(viewport * eyeWidth, 0, eyeWidth, height)
            gray.draw(slot, sx * zoom, sy * zoom)
        }
    }

    /** Stores each fresh camera frame into its recording output, raw or rectified. */
    private fun record(
        rec: Recording,
        shader: StereoShader,
        geometry: StereoGeometry,
        models: List<CameraModel>,
        s: StereoSettings,
        fresh: BooleanArray,
        stabilization: DoubleArray,
    ) {
        val gl = glRecorder ?: try {
            GlRecorder(rec).also { glRecorder = it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot start recording", e)
            rec.error = e.message ?: e.toString()
            recording = null
            return
        }
        for ((index, output) in rec.outputs.withIndex()) {
            val camera = output.camera
            if (!fresh[camera]) continue
            val timestamp = surfaceTextures[camera].timestamp
            if (rec.view == RecordView.RAW) {
                if (gl.capture(index, timestamp) { flip -> shader.drawRaw(textureIds[camera], stMatrices[camera], flip) }) {
                    rec.onFrameStored(output, timestamp, null, null)
                }
            } else {
                val v = rec.eyeView(geometry, s, output)
                val ray = geometry.rayMatrix(
                    camera, v.tanH, v.tanV, v.shift, v.zoom,
                    s.alignPitchDeg.toDouble(), s.alignRollDeg.toDouble(), stabilization,
                )
                val stored = gl.capture(index, timestamp) { flip ->
                    shader.draw(textureIds[camera], if (flip) Mat3.mul(ray, FLIP_Y) else ray, models[camera], stMatrices[camera])
                }
                if (stored) {
                    val k = geometry.rectifiedIntrinsics(output.size.width, output.size.height, v.tanH, v.tanV, v.shift, v.zoom)
                    rec.onFrameStored(output, timestamp, k, stabilization)
                }
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun geometryFor(cams: Cameras, s: StereoSettings): StereoGeometry {
        val key = listOf(cams, s.useFactoryCalibration, s.rotationOverride)
        geometry?.takeIf { key == geometryKey }?.let { return it }
        return StereoGeometry(listOf(cams.left, cams.right), s.useFactoryCalibration, cams.displayRotation, s.rotationOverride)
            .also {
                geometry = it
                geometryKey = key
                geometrySummary = it.summary
            }
    }

    /** Renders both eyes small and unswapped, and compares them; see [AlignmentEstimator]. */
    private fun measureAlignment(
        shader: StereoShader,
        geometry: StereoGeometry,
        models: List<CameraModel>,
        tanH: Double,
        tanV: Double,
        zoom: Double,
        s: StereoSettings,
        stabilization: DoubleArray,
    ) {
        ensureAlignmentFramebuffer()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, alignmentFramebuffer)
        GLES20.glViewport(0, 0, ALIGN_WIDTH, ALIGN_HEIGHT)
        val images = List(2) { camera ->
            val ray = geometry.rayMatrix(
                camera, tanH, tanV, 0.0, zoom, s.alignPitchDeg.toDouble(), s.alignRollDeg.toDouble(), stabilization,
            )
            shader.draw(textureIds[camera], ray, models[camera], stMatrices[camera])
            readLuminance()
        }
        alignmentAttempts++
        AlignmentEstimator.estimate(images[0], images[1], ALIGN_WIDTH, ALIGN_HEIGHT, ALIGN_MAX_SHIFT)?.let { e ->
            val tanPerRow = 2 * tanV / zoom / ALIGN_HEIGHT
            val tanPerColumn = 2 * tanH / zoom / ALIGN_WIDTH
            // Right view lower → lower the left / raise the right: decrease pitch.
            alignmentPitch += -Math.toDegrees(atan(e.meanOffset * tanPerRow))
            // Right view rotated clockwise → rotate it back: increase roll.
            alignmentRoll += Math.toDegrees(atan(e.slope * tanPerRow / tanPerColumn))
        }

        if (alignmentPitch.size >= ALIGN_SAMPLES || alignmentAttempts >= ALIGN_MAX_ATTEMPTS) {
            val result = if (alignmentPitch.size >= ALIGN_SAMPLES / 2) {
                AlignmentResult(alignmentPitch.median(), alignmentRoll.median(), alignmentPitch.size)
            } else {
                null
            }
            val callback = alignmentCallback
            alignmentCallback = null
            alignmentPitch.clear()
            alignmentRoll.clear()
            alignmentAttempts = 0
            mainHandler.post { callback?.invoke(result) }
        }
    }

    private fun ensureAlignmentFramebuffer() {
        if (alignmentFramebuffer != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        alignmentTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, alignmentTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ALIGN_WIDTH, ALIGN_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, ids, 0)
        alignmentFramebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, alignmentFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, alignmentTexture, 0,
        )
    }

    /** Reads the alignment framebuffer as luminance, row 0 at the top. */
    private fun readLuminance(): FloatArray {
        alignmentPixels.position(0)
        GLES20.glReadPixels(0, 0, ALIGN_WIDTH, ALIGN_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, alignmentPixels)
        val out = FloatArray(ALIGN_WIDTH * ALIGN_HEIGHT)
        for (y in 0 until ALIGN_HEIGHT) {
            val src = (ALIGN_HEIGHT - 1 - y) * ALIGN_WIDTH * 4 // GL rows start at the bottom
            for (x in 0 until ALIGN_WIDTH) {
                val i = src + x * 4
                val r = alignmentPixels.get(i).toInt() and 0xff
                val g = alignmentPixels.get(i + 1).toInt() and 0xff
                val b = alignmentPixels.get(i + 2).toInt() and 0xff
                out[y * ALIGN_WIDTH + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return out
    }

    private fun List<Double>.median(): Double = sorted()[size / 2]

    private fun maybeReportStats() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsStart
        if (elapsed < STATS_INTERVAL_MS) return
        val seconds = elapsed / 1000f
        val stats = RenderStats(
            leftFps = newFrames[0] / seconds,
            rightFps = newFrames[1] / seconds,
            drawFps = draws / seconds,
            drawMs = if (draws > 0) drawNanos / 1e6f / draws else 0f,
            sync = sync.takeStats(),
            outputSize = Size(width, height),
            view = viewDescription,
        )
        newFrames.fill(0)
        draws = 0
        drawNanos = 0
        statsStart = now
        mainHandler.post { listener.onRenderStats(stats) }
    }

    /** Call from the main thread after the GLSurfaceView is paused or detached. */
    fun release() {
        recording = null
        surfaceTextures.forEach { it.release() }
        surfaceTextures = emptyList()
        sync.release()
    }

    private companion object {
        const val TAG = "StereoRenderer"
        const val STATS_INTERVAL_MS = 500L
        const val ALIGN_WIDTH = 320
        const val ALIGN_HEIGHT = 180
        const val ALIGN_MAX_SHIFT = 30
        const val ALIGN_SAMPLES = 9
        const val ALIGN_MAX_ATTEMPTS = 40
        const val IMAGE_W = 480 // upright glasses camera images
        const val IMAGE_H = 640
        /** Maps output (u, v, 1) to (u, 1 − v, 1): renders upside down, for glReadPixels. */
        val FLIP_Y = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
    }
}
