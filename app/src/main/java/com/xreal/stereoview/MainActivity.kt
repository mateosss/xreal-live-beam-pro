package com.xreal.stereoview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xreal.stereoview.camera.CameraController
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.camera.CameraTarget
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.databinding.ActivityMainBinding
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.render.GlRecorder
import com.xreal.stereoview.render.StereoPresentation
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.render.Stabilizer
import com.xreal.stereoview.ui.ControlsOverlay
import com.xreal.stereoview.ui.FocusMode
import com.xreal.stereoview.ui.OutputMode
import com.xreal.stereoview.ui.SettingsViewModel
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.CrashReporter
import com.xreal.stereoview.util.DisplayUtils
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Wires the cameras, the stereo renderer and the controls together.
 *
 * Output goes to the glasses' presentation display when one is detected (controls stay on the
 * phone), otherwise full-screen on the phone, which the glasses mirror.
 */
class MainActivity : AppCompatActivity(), CameraController.Listener, StereoRenderer.Listener, ControlsOverlay.Actions {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var controls: ControlsOverlay
    private lateinit var camera: CameraController
    private lateinit var stabilizer: Stabilizer
    private lateinit var cameraManager: CameraManager
    private lateinit var displayManager: DisplayManager

    private var glView: GLSurfaceView? = null
    private var renderer: StereoRenderer? = null
    private var presentation: StereoPresentation? = null
    private var outputDisplay: Display? = null
    private var surfaces: Pair<SurfaceTexture, SurfaceTexture>? = null

    private var rig: StereoRig? = null
    private var streamSize: Size? = null
    private var playing = true
    private var outputActive = false
    private var lastSettings: StereoSettings? = null
    private var permissionRequested = false
    private var recording: Recording? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupOutput() else showPermissionMessage()
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplaysChanged()
        override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(CameraManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        camera = CameraController(this, this)
        stabilizer = Stabilizer(this)
        controls = ControlsOverlay(binding.controls, viewModel, this)
        controls.setPlaying(playing)
        binding.grantButton.setOnClickListener { requestPermission.launch(Manifest.permission.CAMERA) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect(::applySettings)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { controls.render(viewModel.settings.value) }
            }
        }
        CrashReporter.showPendingReport(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        when {
            hasCameraPermission() -> setupOutput()
            // Ask once automatically; after a denial the button asks again.
            !permissionRequested -> {
                permissionRequested = true
                requestPermission.launch(Manifest.permission.CAMERA)
            }
            else -> showPermissionMessage()
        }
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(displayListener)
        teardownOutput()
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
    }

    // region Output

    private fun setupOutput() {
        if (outputActive) return
        outputActive = true
        binding.messageText.visibility = View.GONE
        binding.grantButton.visibility = View.GONE

        val settings = viewModel.settings.value
        val display = if (settings.outputMode == OutputMode.AUTO) DisplayUtils.findGlassesDisplay(displayManager) else null
        val renderer = StereoRenderer(this, stabilizer).also { it.settings = settings }
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(GlRecorder.ConfigChooser())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        renderer.attach(view)

        if (display != null) {
            presentation = StereoPresentation(this, display, view).also { it.show() }
            binding.controls.root.visibility = View.VISIBLE
            binding.messageText.text = "Stereo view is on the glasses.\nAdjust it with the controls below."
            binding.messageText.visibility = View.VISIBLE
        } else {
            binding.glContainer.addView(view)
        }
        this.renderer = renderer
        glView = view
        outputDisplay = display
        stabilizer.start()
        updateStatus()
    }

    private fun teardownOutput() {
        if (!outputActive) return
        stopRecording("Recording stopped: app paused", waitForGl = true)
        outputActive = false
        camera.stopBlocking()
        stabilizer.stop()
        glView?.onPause()
        presentation?.dismiss()
        binding.glContainer.removeAllViews()
        renderer?.release()
        presentation = null
        glView = null
        renderer = null
        outputDisplay = null
        surfaces = null
        streamSize = null
    }

    private fun rebuildOutput() {
        if (!outputActive) return
        teardownOutput()
        setupOutput()
    }

    private fun onDisplaysChanged() {
        if (!outputActive || viewModel.settings.value.outputMode != OutputMode.AUTO) return
        val glasses = DisplayUtils.findGlassesDisplay(displayManager)
        if (glasses?.displayId != outputDisplay?.displayId) rebuildOutput()
    }

    // endregion

    // region Camera

    override fun onSurfacesReady(left: SurfaceTexture, right: SurfaceTexture) {
        if (!outputActive) return
        surfaces = left to right
        if (playing) startCamera()
    }

    private fun startCamera() {
        val (left, right) = surfaces ?: return
        val settings = viewModel.settings.value
        val rig = CameraUtils.selectRig(
            cameraManager, displayRotation(), settings.maxResolution, settings.cameraPair, settings.targetFps,
        )
        if (rig == null) {
            onCameraError("No rear camera found")
            return
        }
        this.rig = rig
        controls.setRig(rig)
        camera.start(rig, left, right, exposureOf(settings))
    }

    override fun onCameraStatus(message: String) {
        Log.i(TAG, message)
        runOnUiThread { controls.setStatus(message) }
    }

    override fun onCameraStreaming(rig: StereoRig, size: Size) {
        runOnUiThread {
            Log.i(TAG, "Streaming ${rig.description} at $size")
            this.rig = rig
            streamSize = size
            controls.setRig(rig)
            renderer?.cameras = cameraModels(rig, size)
            updateStatus()
        }
    }

    /** Geometric models of the rig's two streams; a single camera yields the same model twice. */
    private fun cameraModels(rig: StereoRig, size: Size): StereoRenderer.Cameras? = try {
        fun model(target: CameraTarget) = CameraModel.from(
            target.label,
            cameraManager.getCameraCharacteristics(target.physicalId ?: target.deviceId),
            size,
            rig.undistortInShader,
        )
        val left = model(rig.left)
        val right = if (rig.right == rig.left) left else model(rig.right)
        StereoRenderer.Cameras(left, right, displayRotation())
    } catch (e: Exception) {
        Log.w(TAG, "Cannot build camera models", e)
        null
    }

    /** Measures and corrects the eyes' vertical offset and roll; repeats once to refine. */
    override fun onAutoAlign() {
        val renderer = renderer ?: return
        if (streamSize == null) {
            controls.setStatus("Auto-align needs the cameras running")
            return
        }
        fun round(n: Int) {
            controls.setStatus("Auto-align: measuring (pass $n of 2)… keep the view steady")
            renderer.requestAlignment { result ->
                if (result == null) {
                    controls.setStatus("Auto-align: not enough detail in view. Point at a textured scene and retry.")
                    return@requestAlignment
                }
                viewModel.update {
                    it.copy(
                        alignPitchDeg = (it.alignPitchDeg + result.pitchDeg).toFloat()
                            .coerceIn(-StereoSettings.ALIGN_RANGE_DEG, StereoSettings.ALIGN_RANGE_DEG),
                        alignRollDeg = (it.alignRollDeg + result.rollDeg).toFloat()
                            .coerceIn(-StereoSettings.ALIGN_RANGE_DEG, StereoSettings.ALIGN_RANGE_DEG),
                    )
                }
                val s = viewModel.settings.value
                Log.i(TAG, "Auto-align pass $n: Δpitch %.3f° Δroll %.3f° (%d samples)".format(result.pitchDeg, result.rollDeg, result.samples))
                if (n < 2) {
                    round(n + 1)
                } else {
                    controls.setStatus("Auto-align done: pitch %+.2f°, roll %+.2f°".format(s.alignPitchDeg, s.alignRollDeg))
                }
            }
        }
        round(1)
    }

    override fun onCameraError(message: String) {
        Log.w(TAG, message)
        runOnUiThread { controls.setStatus("⚠ $message") }
    }

    override fun onPlayPause() {
        if (playing) stopRecording("Recording stopped: cameras paused")
        playing = !playing
        controls.setPlaying(playing)
        if (playing) startCamera() else camera.pause()
        updateStatus()
    }

    // endregion

    // region Settings and status

    private fun applySettings(s: StereoSettings) {
        val previous = lastSettings
        lastSettings = s
        renderer?.settings = s
        controls.render(s)
        binding.hudText.visibility = if (s.showHud) View.VISIBLE else View.GONE
        if (previous == null) return

        if (s != previous) recording?.onSettingsChanged(s)
        if (s.outputMode != previous.outputMode) {
            rebuildOutput()
        } else if ((s.maxResolution != previous.maxResolution || s.cameraPair != previous.cameraPair ||
                s.targetFps != previous.targetFps) && playing
        ) {
            stopRecording("Recording stopped: camera settings changed")
            startCamera()
        } else if (exposureOf(s) != exposureOf(previous)) {
            camera.setExposure(exposureOf(s))
        }
    }

    private fun exposureOf(s: StereoSettings) =
        CameraController.Exposure(s.exposure, s.manualExposure, s.exposureTimeNs, s.iso, s.focusMode == FocusMode.INFINITY)

    private var captureStats = ""

    override fun onCaptureStats(exposureTimeNs: Long, iso: Int, frameDurationNs: Long) {
        val text = "sensor: exposure 1/%d s, ISO %d, frame %.1f ms".format(
            if (exposureTimeNs > 0) (1e9 / exposureTimeNs).toInt() else 0, iso, frameDurationNs / 1e6,
        )
        runOnUiThread { captureStats = text }
    }

    override fun onRenderStats(stats: StereoRenderer.RenderStats) {
        if (!viewModel.settings.value.showHud) return
        val sync = stats.sync
        binding.hudText.text = buildString {
            appendLine("L %.1f fps  R %.1f fps  draw %.1f fps (%.2f ms)".format(stats.leftFps, stats.rightFps, stats.drawFps, stats.drawMs))
            appendLine("L/R Δ mean %.2f ms  max %.2f ms  over threshold %d/%d".format(sync.meanDeltaMs, sync.maxDeltaMs, sync.unsyncedPairs, sync.pairs))
            appendLine(
                "stream ${streamSize ?: "-"} (max %.0f fps)  output %d×%d".format(
                    streamSize?.let { rig?.maxFps?.get(it) } ?: 0.0, stats.outputSize.width, stats.outputSize.height,
                )
            )
            appendLine(captureStats)
            append(stats.view)
        }
    }

    private fun updateStatus() {
        val output = outputDisplay?.let { "Glasses: ${DisplayUtils.describe(it)}" } ?: "Output: this screen (mirrored to glasses)"
        val rig = rig
        val source = when {
            !playing -> "Paused"
            rig == null || streamSize == null -> "Starting cameras…"
            else -> "${rig.description} @ $streamSize"
        }
        val warning = if (rig?.baselineVertical == true) "\n⚠ Lenses are stacked vertically in this orientation; depth will look wrong." else ""
        controls.setStatus("$output · $source$warning")
    }

    override fun onShowInfo() {
        val text = buildString {
            appendLine(CrashReporter.deviceInfo(this@MainActivity))
            appendLine("Active rig: ${rig?.description ?: "none"}")
            appendLine("Stream size: ${streamSize ?: "-"}, sensor orientation ${rig?.sensorOrientation ?: "-"}°")
            appendLine("Rectification: ${renderer?.geometrySummary ?: "-"}")
            renderer?.cameras?.let { cams ->
                appendLine("  " + cams.left.describe())
                if (cams.right !== cams.left) appendLine("  " + cams.right.describe())
            }
            appendLine("Undistort in app: ${rig?.undistortInShader}, OIS off: ${rig?.canDisableOis}, gyro: ${stabilizer.available}")
            appendLine("Display rotation: ${CameraUtils.rotationDegrees(displayRotation())}°")
            appendLine("Output: ${outputDisplay?.let(DisplayUtils::describe) ?: "main screen"}")
            appendLine("Displays: " + displayManager.displays.joinToString { DisplayUtils.describe(it) })
            appendLine()
            append(
                try {
                    CameraUtils.describeCameras(cameraManager)
                } catch (e: Exception) {
                    "Camera query failed: ${e.message}"
                }
            )
        }
        val props = CameraUtils.cameraSystemProperties()
        CrashReporter.showReport(
            this,
            "Camera & display info",
            "$text\n--- camera properties ---\n$props\n--- logcat ---\n${CrashReporter.ownLogcat()}",
        )
    }

    override fun cameraPairOptions(): List<Pair<String, String>> =
        CameraUtils.stereoRigs(cameraManager, displayRotation(), viewModel.settings.value.maxResolution)
            .map { it.key to it.description }

    // endregion

    // region Recording

    override fun isRecording() = recording != null

    override fun onRecord() {
        if (recording != null) stopRecording(null) else startRecording()
    }

    private fun startRecording() {
        val renderer = renderer
        val rig = rig
        val size = streamSize
        val cams = renderer?.cameras
        if (renderer == null || rig == null || size == null || cams == null || !playing) {
            controls.setStatus("Recording needs the cameras running")
            return
        }
        val root = Recording.root(this).apply { mkdirs() }
        if (root.usableSpace < MIN_FREE_BYTES) {
            controls.setStatus("⚠ Not enough free space to record (%.1f GB left)".format(root.usableSpace / 1e9))
            return
        }
        val rec = try {
            Recording.start(this, viewModel.settings.value, rig, size, cams, cameraManager, recordingInfo())
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start recording", e)
            controls.setStatus("⚠ Cannot record: ${e.message}")
            return
        }
        recording = rec
        camera.resultListener = rec
        renderer.startRecording(rec)
        controls.setStatus("Recording to ${rec.dir.name}")
        Log.i(TAG, "Recording to ${rec.dir}")
        updateRecordingTimer()
    }

    /** Elapsed time on the button; also stops the recording if the renderer reported an error. */
    private fun updateRecordingTimer() {
        val rec = recording
        if (rec == null) {
            controls.setRecording(null)
            return
        }
        rec.error?.let {
            stopRecording("⚠ Recording failed: $it")
            return
        }
        controls.setRecording((android.os.SystemClock.elapsedRealtimeNanos() - rec.startBoottimeNs) / 1_000_000_000)
        mainHandler.postDelayed(::updateRecordingTimer, 500)
    }

    /**
     * Detaches the recording from the camera and renderer, then finalizes its files in the
     * background. With [waitForGl], waits for the encoder surfaces to be released first (the
     * GL context is about to go away).
     */
    private fun stopRecording(reason: String?, waitForGl: Boolean = false) {
        val rec = recording ?: return
        recording = null
        camera.resultListener = null
        controls.setRecording(null)
        controls.setStatus((reason?.let { "$it. " } ?: "") + "Saving ${rec.dir.name}…")
        fun finish() = thread(name = "RecordingFinish") {
            val summary = try {
                rec.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Finishing recording failed", e)
                "⚠ Saving ${rec.dir.name} failed: ${e.message}"
            }
            Log.i(TAG, summary)
            runOnUiThread { controls.setStatus(summary) }
        }
        val renderer = renderer
        if (renderer == null) {
            finish()
            return
        }
        val released = CountDownLatch(1)
        renderer.stopRecording {
            released.countDown()
            if (!waitForGl) finish()
        }
        if (waitForGl) {
            if (!released.await(1, TimeUnit.SECONDS)) Log.w(TAG, "GL thread did not release the recording in time")
            finish()
        }
    }

    /** Context for metadata.json that only the activity knows. */
    private fun recordingInfo() = JSONObject().apply {
        viewModel.profiles.value.activeProfile?.let { p ->
            put("profile", p.name)
            put("profile_edited", p.differsFrom(viewModel.settings.value))
        } ?: put("profile", JSONObject.NULL)
        put("display_rotation_deg", CameraUtils.rotationDegrees(displayRotation()))
        put("output_display", outputDisplay?.let(DisplayUtils::describe) ?: "main screen")
        put("displays", JSONArray(displayManager.displays.map { DisplayUtils.describe(it) }))
        put("gyro_stabilizer_available", stabilizer.available)
        renderer?.cameras?.let { cams ->
            put("camera_models", JSONArray(listOf(cams.left, cams.right).distinct().map { it.describe() }))
        }
        put("camera_report", runCatching { CameraUtils.describeCameras(cameraManager) }.getOrElse { it.toString() })
    }

    // endregion

    // region Touch: pinch adjusts depth, tap toggles the panel when it covers the stereo view
    // (lazy: the detectors read resources, which aren't available before onCreate)

    private val scaleDetector by lazy { ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            viewModel.update {
                it.copy(depth = (it.depth * factor).coerceIn(StereoSettings.DEPTH_MIN, StereoSettings.DEPTH_MAX))
            }
            return true
        }
    }) }

    private val tapDetector by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (presentation != null || !outputActive) return false
            val panel = binding.controls.root
            val bounds = Rect().also { panel.getGlobalVisibleRect(it) }
            if (panel.visibility == View.VISIBLE && bounds.contains(e.rawX.toInt(), e.rawY.toInt())) return false
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return true
        }
    }) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // endregion

    private companion object {
        const val TAG = "StereoView"
        const val MIN_FREE_BYTES = 500_000_000L
    }

    private fun displayRotation(): Int = display?.rotation ?: 0

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionMessage() {
        binding.messageText.setText(R.string.permission_needed)
        binding.messageText.visibility = View.VISIBLE
        binding.grantButton.visibility = View.VISIBLE
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
