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
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.FrameSynchronizer
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.ui.StereoSettings
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Composites the left and right camera textures into one side-by-side frame: the left half of
 * the surface is the left eye, the right half the right eye.
 *
 * Listener callbacks are delivered on the main thread.
 */
class StereoRenderer(private val listener: Listener) : GLSurfaceView.Renderer {
    interface Listener {
        /** New camera targets (0 = left camera, 1 = right camera); called after every GL context creation. */
        fun onSurfacesReady(left: SurfaceTexture, right: SurfaceTexture)
        fun onRenderStats(stats: RenderStats)
    }

    data class StreamInfo(val size: Size, val sensorOrientation: Int, val displayRotationDegrees: Int)

    data class RenderStats(
        val leftFps: Float,
        val rightFps: Float,
        val drawFps: Float,
        val drawMs: Float,
        val sync: FrameSynchronizer.Stats,
        val outputSize: Size,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestRender: () -> Unit = {}
    private val sync = FrameSynchronizer { requestRender() }

    @Volatile
    var settings = StereoSettings()
        set(value) {
            field = value
            sync.timeoutMs = value.syncThresholdMs.toLong()
            requestRender()
        }

    @Volatile
    var stream: StreamInfo? = null
        set(value) {
            field = value
            requestRender()
        }

    // GL thread state
    private var shader: StereoShader? = null
    private val textureIds = IntArray(2)
    private var surfaceTextures: List<SurfaceTexture> = emptyList()
    private val stMatrices = Array(2) { FloatArray(16).also { Matrix.setIdentityM(it, 0) } }
    private val viewMatrix = FloatArray(16)
    private var width = 1
    private var height = 1

    // Stats, GL thread only
    private val newFrames = IntArray(2)
    private var draws = 0
    private var drawNanos = 0L
    private var statsStart = SystemClock.elapsedRealtime()

    /** Binds the renderer to its view; draws are only requested when frames arrive. */
    fun attach(view: GLSurfaceView) {
        requestRender = view::requestRender
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context invalidates any previous textures; replace the SurfaceTextures too.
        surfaceTextures.forEach { it.release() }
        shader = StereoShader()
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

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val info = stream
        val shader = shader
        if (info != null && shader != null) {
            val s = settings
            for (viewport in 0..1) {
                val camera = if (s.swapEyes) 1 - viewport else viewport
                drawEye(shader, viewport, camera, info, s)
            }
        }

        draws++
        drawNanos += SystemClock.elapsedRealtimeNanos() - start
        maybeReportStats()
    }

    /** Draws camera [camera] into half [viewport] (0 = left, 1 = right) of the SBS frame. */
    private fun drawEye(shader: StereoShader, viewport: Int, camera: Int, info: StreamInfo, s: StereoSettings) {
        val eyeWidth = width / 2
        GLES20.glViewport(viewport * eyeWidth, 0, eyeWidth, height)

        val rotation = if (s.rotationOverride >= 0) s.rotationOverride
        else CameraUtils.imageRotation(info.sensorOrientation, info.displayRotationDegrees)
        val cameraAspect = if (rotation % 180 == 0) info.size.width.toFloat() / info.size.height
        else info.size.height.toFloat() / info.size.width
        val eyeAspect = eyeWidth.toFloat() / height

        var cropX = 1f
        var cropY = 1f
        var quadX = 1f
        var quadY = 1f
        when (s.scaleMode) {
            ScaleMode.FILL ->
                if (cameraAspect > eyeAspect) cropX = eyeAspect / cameraAspect else cropY = cameraAspect / eyeAspect
            ScaleMode.FIT ->
                if (cameraAspect > eyeAspect) quadY = eyeAspect / cameraAspect else quadX = cameraAspect / eyeAspect
            ScaleMode.STRETCH -> Unit
        }

        // Positive convergence moves the left image left and the right image right, by
        // convergencePx render pixels each.
        val shift = (if (viewport == 0) 1f else -1f) * s.convergencePx / (eyeWidth * quadX)
        val zoom = s.depth.coerceIn(StereoSettings.DEPTH_MIN, StereoSettings.DEPTH_MAX)

        // camera = R(rotation) · S(crop / zoom) · (screen + shift), all about the image center.
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(viewMatrix, 0, cropX / zoom, cropY / zoom, 1f)
        Matrix.translateM(viewMatrix, 0, -0.5f + shift, -0.5f, 0f)

        shader.draw(textureIds[camera], viewMatrix, stMatrices[camera], quadX, quadY)
    }

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
        )
        newFrames.fill(0)
        draws = 0
        drawNanos = 0
        statsStart = now
        mainHandler.post { listener.onRenderStats(stats) }
    }

    /** Call from the main thread after the GLSurfaceView is paused or detached. */
    fun release() {
        surfaceTextures.forEach { it.release() }
        surfaceTextures = emptyList()
        sync.release()
    }

    private companion object {
        const val TAG = "StereoRenderer"
        const val STATS_INTERVAL_MS = 500L
    }
}
