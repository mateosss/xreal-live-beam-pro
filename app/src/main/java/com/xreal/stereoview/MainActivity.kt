package com.xreal.stereoview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.camera.CameraController
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.camera.CameraTarget
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.databinding.ActivityMainBinding
import com.xreal.stereoview.device.DeviceSession
import com.xreal.stereoview.device.GlassesSession
import com.xreal.stereoview.device.OakSession
import com.xreal.stereoview.device.PhoneSession
import com.xreal.stereoview.device.SessionHost
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.glasses.GlassesImuCalibration
import com.xreal.stereoview.glasses.GlassesProtocol
import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.preview.CapturePreview
import com.xreal.stereoview.record.ActiveRecording
import com.xreal.stereoview.record.GlassesRecording
import com.xreal.stereoview.record.OakRecording
import com.xreal.stereoview.record.OakRgbFormat
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.record.RecordingService
import com.xreal.stereoview.record.RecordingStorage
import com.xreal.stereoview.render.RenderThread
import com.xreal.stereoview.render.Stabilizer
import com.xreal.stereoview.render.StereoPresentation
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.ControlsOverlay
import com.xreal.stereoview.ui.Dialogs
import com.xreal.stereoview.ui.FilePicker
import com.xreal.stereoview.ui.FocusMode
import com.xreal.stereoview.ui.OutputMode
import com.xreal.stereoview.ui.PreviewStyle
import com.xreal.stereoview.ui.SettingsViewModel
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.CrashReporter
import com.xreal.stereoview.util.DisplayUtils
import com.xreal.stereoview.util.UsbProbe
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
class MainActivity : AppCompatActivity(), StereoRenderer.Listener, ControlsOverlay.Actions {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var controls: ControlsOverlay
    private lateinit var stabilizer: Stabilizer
    private lateinit var displayManager: DisplayManager

    // The camera sources; one runs at a time (the profile's).
    private lateinit var phone: PhoneSession
    private lateinit var glasses: GlassesSession
    private lateinit var oak: OakSession
    private lateinit var sessions: List<DeviceSession>

    private var glView: SurfaceView? = null
    private var renderThread: RenderThread? = null

    // Read by the devices' threads.
    @Volatile private var renderer: StereoRenderer? = null

    @Volatile private var recording: ActiveRecording? = null

    /** Portrait capture view (Settings → Phone preview), while shown; camera threads feed it. */
    @Volatile private var capture: CapturePreview? = null
    private var presentation: StereoPresentation? = null
    private var outputDisplay: Display? = null
    private var surfaces: Pair<SurfaceTexture, SurfaceTexture>? = null

    private var playing = true
    private var outputActive = false
    private var lastSettings: StereoSettings? = null
    private var permissionRequested = false

    /** The cameras should start as soon as the stored settings (camera source etc.) are loaded. */
    private var cameraStartPending = false
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

        displayManager = getSystemService(DisplayManager::class.java)
        phone = PhoneSession(host, { surfaces }, ::displayRotation)
        glasses = GlassesSession(host)
        oak = OakSession(host)
        sessions = listOf(phone, glasses, oak)
        stabilizer = Stabilizer(this)
        controls = ControlsOverlay(binding.controls, viewModel, this)
        controls.setPlaying(playing)
        controls.setPortrait(isPortrait())
        binding.grantButton.setOnClickListener { requestPermission.launch(Manifest.permission.CAMERA) }
        intent.getStringExtra(EXTRA_PROFILE)?.let(viewModel::startWithProfile)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back returns to the device's profiles; a running recording is stopped first.
                if (recording == null) {
                    finish()
                } else {
                    Dialogs.confirm(this@MainActivity, "Stop the recording and go back to the profiles?", "Stop and go back") {
                        stopRecording(null)
                        finish()
                    }
                }
            }
        })

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
        lifecycleScope.launch {
            viewModel.loadedFlow.collect { loaded ->
                if (loaded && cameraStartPending) {
                    cameraStartPending = false
                    startCamera()
                }
            }
        }
        CrashReporter.showPendingReport(this)
    }

    /** Rotation (the activity handles it itself, so cameras and recordings keep running). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controls.setPortrait(isPortrait())
        updateCapture()
        hideSystemBars()
        // The Beam Pro's stereo pair and rectification depend on how the screen is turned:
        // reopen the cameras for the new orientation, or after the recording if one runs.
        if (viewModel.settings.value.cameraSource == CameraSource.BEAM_PRO && playing && outputActive) {
            if (recording == null) startCamera() else rotationRestartPending = true
        }
        updateStatus()
    }

    private var rotationRestartPending = false

    private fun isPortrait() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Started again from the profile list (e.g. while recording in the background).
        val name = intent.getStringExtra(EXTRA_PROFILE) ?: return
        if (recording == null && name != viewModel.profiles.value.active) viewModel.selectProfile(name)
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
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

    /**
     * Cameras are released when the screen is really hidden (onStop), not on every pause: a
     * permission dialog or the invisible USB attach activity also pause it, and the OAK
     * re-enumerates whenever it boots or closes, so releasing on pause made a loop (stop, the
     * camera resets and re-attaches, the attach activity pauses us, start, boot, re-attach…).
     */
    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        // A recording keeps everything running (screen locked, app in the background), held
        // by RecordingService; otherwise the cameras are released for other apps.
        if (recording == null) teardownOutput()
        super.onStop()
    }

    override fun onDestroy() {
        // E.g. swiped away from recents while recording in the background: save what was recorded.
        if (recording != null) teardownOutput()
        sessions.forEach { it.release() }
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
        val renderThread = RenderThread(renderer)
        val view = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                    renderThread.setWindow(holder.surface, width, height)
                override fun surfaceDestroyed(holder: SurfaceHolder) = renderThread.setWindow(null, 0, 0)
            })
        }
        this.renderThread = renderThread

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
        updateCapture()
        updateStatus()
    }

    private fun teardownOutput() {
        if (!outputActive) return
        stopRecording("Recording stopped: app paused", waitForGl = true)
        outputActive = false
        sessions.forEach { it.shutdown() }
        stabilizer.stop()
        removeCapture()
        presentation?.dismiss()
        binding.glContainer.removeAllViews()
        renderThread?.quit()
        renderThread = null
        renderer?.release()
        presentation = null
        glView = null
        renderer = null
        outputDisplay = null
        surfaces = null
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
        if (!playing) return
        if (viewModel.loaded) startCamera() else cameraStartPending = true
    }

    /** Starts the profile's camera source; the others stop. */
    private fun startCamera() {
        if (surfaces == null) return
        val session = session()
        for (other in sessions) if (other !== session) other.onOtherSourceStarting()
        session.start()
    }

    /** The session of [source], by default the current settings' camera source. */
    private fun session(source: CameraSource = viewModel.settings.value.cameraSource) = sessions.first { it.source == source }

    /** Measures and corrects the eyes' vertical offset and roll; repeats once to refine. */
    override fun onAutoAlign() {
        val renderer = renderer ?: return
        val s0 = viewModel.settings.value
        session(s0.cameraSource).alignBlocker(s0)?.let {
            controls.setStatus(it)
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
                    it.withAlignment(
                        (it.alignPitchDeg + result.pitchDeg).toFloat()
                            .coerceIn(-StereoSettings.ALIGN_RANGE_DEG, StereoSettings.ALIGN_RANGE_DEG),
                        (it.alignRollDeg + result.rollDeg).toFloat()
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

    override fun onPlayPause() {
        if (playing) stopRecording("Recording stopped: cameras paused")
        playing = !playing
        controls.setPlaying(playing)
        if (playing) {
            startCamera()
        } else {
            sessions.forEach { it.stop() }
        }
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
        if (previous == null || captureKey(s) != captureKey(previous)) updateCapture()
        if (cameraStartPending && viewModel.loaded) {
            cameraStartPending = false
            startCamera()
        }
        if (previous == null) return

        if (s != previous) recording?.onSettingsChanged(s)
        if (s.outputMode != previous.outputMode) {
            rebuildOutput()
        } else if (s.cameraSource != previous.cameraSource && playing) {
            stopRecording("Recording stopped: camera source changed")
            binding.hudText.text = ""
            startCamera()
        } else {
            val session = session(s.cameraSource)
            val reason = if (playing) session.restartReason(s, previous) else null
            if (reason != null) {
                stopRecording(reason)
                session.restart()
            } else {
                session.applyLive(s, previous)
            }
        }
    }

    override fun onRenderStats(stats: StereoRenderer.RenderStats) {
        if (!viewModel.settings.value.showHud) return
        binding.hudText.text = buildString {
            appendLine(session().hud(stats))
            recording?.let { appendLine(it.backlog) }
            append(stats.view)
        }
    }

    private fun updateStatus() {
        val output = outputDisplay?.let { "Glasses: ${DisplayUtils.describe(it)}" } ?: "Output: this screen (mirrored to glasses)"
        val session = session()
        val source = if (playing) session.status else "Paused"
        val warning = session.statusWarning
        controls.setStatus("$output · $source$warning")
    }

    override fun onShowInfo() {
        val text = buildString {
            appendLine(CrashReporter.deviceInfo(this@MainActivity))
            val rig = phone.rig
            appendLine("Active rig: ${rig?.description ?: "none"}")
            appendLine("Stream size: ${phone.streamSize ?: "-"}, sensor orientation ${rig?.sensorOrientation ?: "-"}°")
            appendLine("Rectification: ${renderer?.geometrySummary ?: "-"}")
            appendLine("Glasses rectification: ${renderer?.glassesGeometrySummary ?: "-"}")
            val g = glasses.device
            if (g.imuReadsQueued > 0) appendLine("Glasses IMU: ${g.imuReadsQueued} reads queued (of ${g.imuReadsWanted} wanted)")
            appendLine("Glasses camera controls: ${g.controls?.report ?: "glasses cameras not started"}")
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
                    CameraUtils.describeCameras(phone.cameraManager)
                } catch (e: Exception) {
                    "Camera query failed: ${e.message}"
                }
            )
        }
        val props = CameraUtils.cameraSystemProperties()
        val usb = runCatching { UsbProbe(this).describe() }.getOrElse { "USB query failed: ${it.message}" }
        CrashReporter.showReport(
            this,
            "Camera & display info",
            "$text\n--- camera properties ---\n$props\n--- USB ---\n$usb\n--- logcat ---\n${CrashReporter.ownLogcat()}",
        )
    }

    /**
     * Lists USB devices and, for XREAL glasses, asks for access and checks which interfaces
     * (cameras, IMU) the app can claim and read.
     */
    override fun onUsbProbe() {
        val probe = UsbProbe(this)
        val glasses = probe.glasses()
        fun show(report: String) = CrashReporter.showReport(this, "Glasses USB probe", report)
        fun run(device: android.hardware.usb.UsbDevice) {
            controls.setStatus("USB probe: waiting for access to ${device.productName ?: device.deviceName}…")
            probe.requestAndProbe(device) { result ->
                controls.setStatus("USB probe finished")
                val report = "${result.report}\n\n--- all USB devices ---\n${probe.describe()}"
                val dir = java.io.File(getExternalFilesDir(null), "glasses_probe")
                fun capture(force: Boolean) {
                    controls.setStatus("Capturing a frame from the glasses camera…")
                    probe.grabFrame(device, dir, force) { grab ->
                        controls.setStatus("Glasses camera capture finished")
                        val note = if (force) "\n\nThe camera now stays detached from the system until the glasses are replugged." else ""
                        show("--- frame capture${if (force) " (forced)" else ""} ---\n$grab$note\n\n--- probe ---\n$report")
                    }
                }
                if (result.cameraHeldBySystem) {
                    // Offer to take the camera (only the video interfaces) from its current owner.
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Glasses camera is held by the system")
                        .setMessage(
                            "The app can take over only the camera's two USB video interfaces to capture one test frame. " +
                                "The display, head tracking (IMU) and audio are not touched. The camera then stays " +
                                "detached from the system until you unplug and replug the glasses; until then, " +
                                "features that use the glasses' camera (such as 6DoF in Nebula) won't work.",
                        )
                        .setPositiveButton("Take over and capture") { _, _ -> capture(force = true) }
                        .setNegativeButton("Just the report") { _, _ -> show(report) }
                        .show()
                    return@requestAndProbe
                }
                if (!result.canGrab) {
                    show(report)
                    return@requestAndProbe
                }
                // The camera is ours to use: offer to start it and grab one frame.
                MaterialAlertDialogBuilder(this)
                    .setTitle("Glasses camera is accessible")
                    .setMessage(
                        "Capture one frame from it? This starts the camera with standard USB video commands, " +
                            "reads for up to 3 seconds and stops it again. The frame is saved for inspection.",
                    )
                    .setPositiveButton("Capture") { _, _ -> capture(force = false) }
                    .setNegativeButton("Just the report") { _, _ -> show(report) }
                    .show()
            }
        }
        val label = { d: android.hardware.usb.UsbDevice -> "%04x:%04x %s".format(d.vendorId, d.productId, d.productName ?: d.deviceName) }
        when (glasses.size) {
            0 -> show("No XREAL glasses (USB vendor 3318) visible to apps.\n\n${probe.describe()}")
            1 -> MaterialAlertDialogBuilder(this)
                .setTitle("Probe ${label(glasses[0])}")
                .setMessage(
                    "Android will ask to give this app access to the glasses. The probe then tries to claim each " +
                        "USB interface without taking it from the system, and reads briefly from it. Nothing is " +
                        "sent to the glasses.",
                )
                .setPositiveButton("Probe") { _, _ -> run(glasses[0]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> MaterialAlertDialogBuilder(this)
                .setTitle("Probe which USB device? (asks for access, only reads)")
                .setItems(glasses.map(label).toTypedArray()) { _, i -> run(glasses[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun cameraPairOptions(): List<Pair<String, String>> =
        CameraUtils.stereoRigs(phone.cameraManager, displayRotation(), viewModel.settings.value.maxResolution)
            .map { it.key to it.description }

    // endregion

    // region Recording

    override fun isRecording() = recording != null
    override fun recordingDir() = recording?.dir

    override fun onRecord() {
        if (recording != null) stopRecording(null) else startRecording()
    }

    private fun startRecording() = session().startRecording()

    /**
     * Starts a recording made by [create] if there is room for it, keeps it running in the
     * background and shows its progress. Returns it, or null (and says why) if it can't start.
     */
    private fun <R : ActiveRecording> beginRecording(what: String, usb: Boolean, create: () -> R): R? {
        val root = RecordingStorage.root(this).apply { mkdirs() }
        if (root.usableSpace < MIN_FREE_BYTES) {
            controls.setStatus("⚠ Not enough free space to record (%.1f GB left)".format(root.usableSpace / 1e9))
            return null
        }
        val rec = try {
            create()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start recording", e)
            controls.setStatus("⚠ Cannot record: ${e.message}")
            return null
        }
        recording = rec
        keepRecordingInBackground(rec, usb)
        controls.setStatus("$what to ${rec.dir.name}")
        Log.i(TAG, "Recording to ${rec.dir}")
        updateRecordingTimer()
        return rec
    }

    /** Elapsed time on the button; also stops the recording if the renderer reported an error. */
    private fun updateRecordingTimer() {
        val rec = recording
        if (rec == null) {
            controls.setRecording(null)
            return
        }
        sessions.firstNotNullOfOrNull { it.streamLost(rec) }?.let { rec.error = it }
        rec.writeErrors()?.let { rec.error = "writing failed ($it)" }
        if (rec.dir.usableSpace < STOP_FREE_BYTES) rec.error = "storage is almost full"
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
        RecordingService.onStopRequested = null
        if (rotationRestartPending) {
            rotationRestartPending = false
            mainHandler.post {
                if (playing && outputActive && recording == null && viewModel.settings.value.cameraSource == CameraSource.BEAM_PRO) startCamera()
            }
        }
        sessions.forEach { it.onRecordingStopped() }
        val glRecording = rec as? Recording
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
            runOnUiThread {
                // The service keeps the process alive until the files are complete.
                if (recording == null) RecordingService.stop(applicationContext)
                if (isDestroyed) return@runOnUiThread
                controls.setStatus(summary)
                // Stopped while in the background: now release the cameras as onPause would have.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && recording == null) teardownOutput()
            }
        }
        val renderer = renderer
        if (renderer == null || glRecording == null) {
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

    /**
     * Starts [RecordingService] so the recording continues with the screen locked; asks once
     * for the notification permission that lets its notification (with a Stop button) show.
     */
    private fun keepRecordingInBackground(rec: ActiveRecording, usb: Boolean) {
        RecordingService.onStopRequested = { if (recording === rec) stopRecording("Recording stopped from the notification") }
        RecordingService.start(this, rec.dir.name, usb)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionAsked
        ) {
            notificationPermissionAsked = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private var notificationPermissionAsked = false
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val filePicker = FilePicker(this)

    override fun pickFile(onPicked: (android.net.Uri, String?) -> Unit) = filePicker.pick(onPicked)

    // endregion

    /** What the camera sources need from this screen. */
    private val host = object : SessionHost {
        override val activity: AppCompatActivity get() = this@MainActivity
        override val controls: ControlsOverlay get() = this@MainActivity.controls
        override val settings: StereoSettings get() = viewModel.settings.value
        override val playing: Boolean get() = this@MainActivity.playing
        override val renderer: StereoRenderer? get() = this@MainActivity.renderer
        override val recording: ActiveRecording? get() = this@MainActivity.recording
        override val capture: CapturePreview? get() = this@MainActivity.capture
        override fun showStatus(message: String) = runOnUiThread { controls.setStatus(message) }
        override fun showError(message: String) = runOnUiThread { controls.setStatus("⚠ $message") }
        override fun refreshStatus() = runOnUiThread { updateStatus() }
        override fun stopRecording(reason: String) = this@MainActivity.stopRecording(reason)
        override fun <R : ActiveRecording> beginRecording(what: String, usb: Boolean, create: () -> R): R? =
            this@MainActivity.beginRecording(what, usb, create)
        override fun recordingInfo(): JSONObject = this@MainActivity.recordingInfo()
    }

    // region Recording metadata

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
        put("camera_report", runCatching { CameraUtils.describeCameras(phone.cameraManager) }.getOrElse { it.toString() })
        glasses.device.controls?.let { put("glasses_camera_controls", it.report) }
        if (glasses.device.imuReadsQueued > 0) put("glasses_imu_reads_queued", glasses.device.imuReadsQueued)
    }

    // endregion

    // region Capture view (portrait)

    /** What the capture view's layout depends on. */
    private fun captureKey(s: StereoSettings) = listOf(s.previewStyle, s.cameraSource, s.oakRgbHeight, s.oakImuRateHz, s.glassesImu)

    /**
     * Shows the capture view in portrait when the profile asks for it (Settings → Phone
     * preview): the OAK's and the glasses' frames go to its tiles; the phone's own cameras only
     * reach the renderer, so its view is laid over the camera area instead.
     */
    private fun updateCapture() {
        if (!outputActive) return
        val s = viewModel.settings.value
        if (!isPortrait() || s.previewStyle != PreviewStyle.CAPTURE) {
            removeCapture()
            return
        }
        val view = capture ?: CapturePreview(this).also { c ->
            binding.root.addView(c, 1, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            c.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeRendererOverCapture() }
            binding.controls.root.addOnLayoutChangeListener(controlsLayoutListener)
            capture = c
        }
        view.clear()
        sessions.forEach { it.onCaptureHidden() }
        val session = session(s.cameraSource)
        val layout = session.captureLayout(s)
        view.setTiles(layout.tiles)
        view.setImuTitle(layout.imuTitle)
        session.onCaptureShown(view)
        for (plot in listOf(view.gyro, view.accel)) plot.emptyText = if (layout.imuOn) "waiting for samples…" else "IMU off"
        if (session.drawsInCaptureArea && presentation != null) view.setCameraMessage("The stereo view is on the glasses")
        if (presentation == null) binding.messageText.visibility = View.GONE
        updateCaptureMargin()
        placeRendererOverCapture()
    }

    private fun removeCapture() {
        val c = capture ?: return
        capture = null
        sessions.forEach { it.onCaptureHidden() }
        binding.controls.root.removeOnLayoutChangeListener(controlsLayoutListener)
        binding.root.removeView(c)
        c.release()
        binding.glContainer.layoutParams = (binding.glContainer.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            topMargin = 0
            leftMargin = 0
        }
        if (presentation != null) binding.messageText.visibility = View.VISIBLE
    }

    private val controlsLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCaptureMargin() }

    /** The capture view ends where the controls panel begins. */
    private fun updateCaptureMargin() {
        val c = capture ?: return
        val panel = binding.controls.root
        val bottom = if (panel.visibility == View.VISIBLE) panel.height else 0
        val lp = c.layoutParams as android.widget.FrameLayout.LayoutParams
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            c.layoutParams = lp
        }
    }

    /** Phone cameras: the renderer covers the capture view's camera area; otherwise the whole screen, under it. */
    private fun placeRendererOverCapture() {
        val c = capture ?: return
        val lp = binding.glContainer.layoutParams as android.widget.FrameLayout.LayoutParams
        val overArea = session().drawsInCaptureArea
        val area = c.cameraArea
        val (w, h, top, left) = if (overArea && area.height > 0) {
            listOf(area.width, area.height, c.top + area.top, c.left + area.left)
        } else {
            listOf(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 0)
        }
        if (lp.width != w || lp.height != h || lp.topMargin != top || lp.leftMargin != left) {
            lp.width = w
            lp.height = h
            lp.topMargin = top
            lp.leftMargin = left
            binding.glContainer.layoutParams = lp
        }
        // Over the camera area the renderer must be on top of the capture view's background.
        if (overArea) binding.glContainer.bringToFront() else if (binding.root.indexOfChild(binding.glContainer) != 0) {
            binding.root.removeView(binding.glContainer)
            binding.root.addView(binding.glContainer, 0)
        }
        // Keep the overlays (messages, HUD, controls) above the renderer.
        for (v in listOf(binding.messageText, binding.grantButton, binding.hudText, binding.controls.root)) v.bringToFront()
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
        // The capture view's tiles take pinches and taps themselves (zoom).
        if (capture != null) return super.dispatchTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // endregion

    companion object {
        /** Name of the profile to start with (from [HomeActivity]). */
        const val EXTRA_PROFILE = "profile"
        private const val TAG = "StereoView"
        private const val MIN_FREE_BYTES = 500_000_000L

        /** Recordings stop below this, leaving room to finish their files. */
        private const val STOP_FREE_BYTES = 200_000_000L
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
