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
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.databinding.ActivityMainBinding
import com.xreal.stereoview.render.StereoPresentation
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.ControlsOverlay
import com.xreal.stereoview.ui.OutputMode
import com.xreal.stereoview.ui.SettingsViewModel
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.CrashReporter
import com.xreal.stereoview.util.DisplayUtils
import kotlinx.coroutines.launch

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
        controls = ControlsOverlay(binding.controls, viewModel, this)
        controls.setPlaying(playing)
        binding.grantButton.setOnClickListener { requestPermission.launch(Manifest.permission.CAMERA) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect(::applySettings)
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
        val renderer = StereoRenderer(this).also { it.settings = settings }
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
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
        updateStatus()
    }

    private fun teardownOutput() {
        if (!outputActive) return
        outputActive = false
        camera.stopBlocking()
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
        val rig = CameraUtils.selectRig(cameraManager, displayRotation(), settings.maxResolution, settings.cameraPair)
        if (rig == null) {
            onCameraError("No rear camera found")
            return
        }
        this.rig = rig
        controls.setExposureRange(rig.exposureRange)
        camera.start(rig, left, right, settings.exposure)
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
            controls.setExposureRange(rig.exposureRange)
            renderer?.stream = StereoRenderer.StreamInfo(
                size,
                rig.sensorOrientation,
                CameraUtils.rotationDegrees(displayRotation()),
            )
            updateStatus()
        }
    }

    override fun onCameraError(message: String) {
        Log.w(TAG, message)
        runOnUiThread { controls.setStatus("⚠ $message") }
    }

    override fun onPlayPause() {
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

        if (s.outputMode != previous.outputMode) {
            rebuildOutput()
        } else if ((s.maxResolution != previous.maxResolution || s.cameraPair != previous.cameraPair) && playing) {
            startCamera()
        } else if (s.exposure != previous.exposure) {
            camera.setExposure(s.exposure)
        }
    }

    override fun onRenderStats(stats: StereoRenderer.RenderStats) {
        if (!viewModel.settings.value.showHud) return
        val sync = stats.sync
        binding.hudText.text = buildString {
            appendLine("L %.1f fps  R %.1f fps  draw %.1f fps (%.2f ms)".format(stats.leftFps, stats.rightFps, stats.drawFps, stats.drawMs))
            appendLine("L/R Δ mean %.2f ms  max %.2f ms  over threshold %d/%d".format(sync.meanDeltaMs, sync.maxDeltaMs, sync.unsyncedPairs, sync.pairs))
            append("stream ${streamSize ?: "-"}  output ${stats.outputSize.width}×${stats.outputSize.height}")
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
