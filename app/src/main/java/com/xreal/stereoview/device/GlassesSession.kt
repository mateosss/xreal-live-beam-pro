package com.xreal.stereoview.device

import android.os.SystemClock
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.glasses.GlassesImuCalibration
import com.xreal.stereoview.glasses.GlassesProtocol
import com.xreal.stereoview.preview.CapturePreview
import com.xreal.stereoview.record.ActiveRecording
import com.xreal.stereoview.record.GlassesRecording
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.StereoSettings

/**
 * The Air 2 Ultra's tracking cameras (and optionally its IMU) over USB: taken from the system
 * after the user agrees once per screen and grants USB access.
 */
class GlassesSession(host: SessionHost) : DeviceSession(host) {
    override val source = CameraSource.GLASSES
    val device = GlassesDevice(host.activity.applicationContext, Listener())

    /** The user accepted taking the glasses' camera (and IMU) from the system on this screen. */
    private var takeoverConfirmed = false

    /** The glasses' factory calibration (JSON), once read: recordings started later get it too. */
    @Volatile private var calibration: String? = null

    /** Converts IMU samples for the capture view as imu0 records them. */
    @Volatile private var imuCalibration = GlassesImuCalibration.parse(null)
    private var imuActive = false
    private var statsTime = 0L

    override val isRunning: Boolean get() = device.isRunning

    private inner class Listener : GlassesDevice.Listener {
        override fun onStatus(message: String) {
            Log.i(TAG, message)
            host.showStatus(message)
            host.refreshStatus()
        }

        override fun onError(message: String) {
            Log.w(TAG, message)
            host.showError(message)
        }

        override fun onStereoFrame(frame: GlassesDevice.StereoFrame) {
            host.renderer?.glassesFrame = frame
            (host.recording as? GlassesRecording)?.onStereoFrame(frame)
            host.capture?.let {
                it.gray(0, frame.left.image, IMAGE_W, IMAGE_H)
                it.gray(1, frame.right.image, IMAGE_W, IMAGE_H)
            }
        }

        override fun onImu(sample: GlassesProtocol.ImuSample, arrivalNs: Long) {
            (host.recording as? GlassesRecording)?.onImu(sample, arrivalNs)
            host.capture?.let {
                // As recorded in imu0: calibrated, in Monado's IMU axes.
                val g = imuCalibration.gyro(sample)
                val a = imuCalibration.accel(sample)
                it.gyro.add(sample.timestampNs, g[0].toFloat(), g[1].toFloat(), g[2].toFloat())
                it.accel.add(sample.timestampNs, a[0].toFloat(), a[1].toFloat(), a[2].toFloat())
            }
        }

        override fun onCalibration(json: String) {
            calibration = json
            imuCalibration = GlassesImuCalibration.parse(json)
            Log.i(TAG, "Glasses calibration: ${json.length} bytes")
            (host.recording as? GlassesRecording)?.onCalibration(json)
        }

        override fun onControls(controls: GlassesDevice.CameraControls) {
            host.activity.runOnUiThread { host.controls.setGlassesControls(controls) }
        }
    }

    override fun start() {
        val withImu = host.settings.glassesImu
        if (device.isRunning) stop()
        val usb = device.find()
        if (usb == null) {
            host.showError("No Air 2 Ultra found over USB. Connect the glasses, or switch the camera source to the Beam Pro.")
            return
        }
        fun go() = device.requestPermission(usb) { granted ->
            if (!granted) {
                host.showError("USB access to the glasses was denied")
                return@requestPermission
            }
            val s = host.settings
            if (!host.playing || s.cameraSource != CameraSource.GLASSES) return@requestPermission
            imuActive = withImu
            statsTime = SystemClock.elapsedRealtime()
            device.start(usb, withImu, exposureOf(s), s.buffers.glassesImuS * GlassesDevice.IMU_RATE_HZ)
            host.showStatus("Starting the glasses cameras…")
            host.refreshStatus()
        }
        if (takeoverConfirmed) {
            go()
            return
        }
        MaterialAlertDialogBuilder(host.activity)
            .setTitle("Use the glasses' cameras")
            .setMessage(
                "The app will take the glasses' tracking cameras" + (if (withImu) " and IMU" else "") +
                    " away from the system to stream them directly. Until you unplug and replug the glasses, " +
                    "XREAL features that use them" + (if (withImu) ", including head tracking," else "") +
                    " won't work. The display is not affected.",
            )
            .setPositiveButton("Take over") { _, _ ->
                takeoverConfirmed = true
                go()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> host.showStatus("Glasses cameras not started") }
            .show()
    }

    override fun stop() {
        if (!device.isRunning) return
        if (host.recording is GlassesRecording) host.stopRecording("Recording stopped: glasses cameras stopped")
        device.stop()
        host.renderer?.glassesFrame = null
        imuActive = false
        host.controls.setGlassesControls(null)
    }

    override fun restartReason(s: StereoSettings, previous: StereoSettings): String? =
        if (needsRestart(s, previous)) "Recording stopped: glasses settings changed" else null

    override fun applyLive(s: StereoSettings, previous: StereoSettings) {
        if (exposureOf(s) != exposureOf(previous) && device.isRunning) device.setExposure(exposureOf(s))
    }

    override fun hud(stats: StereoRenderer.RenderStats): String {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - statsTime) / 1000.0).coerceAtLeast(1e-3)
        statsTime = now
        val g = device.takeStats()
        return buildString {
            appendLine(
                "glasses: %.1f frames/s (%.1f pairs/s)  %.1f MB/s  draw %.1f fps".format(
                    g.frames / seconds, g.pairs / seconds, g.megabytes / seconds, stats.drawFps,
                ),
            )
            appendLine("incomplete %d  dropped payloads %d".format(g.incomplete, g.dropped))
            append(if (imuActive) "glasses IMU %.0f Hz".format(g.imuSamples / seconds) else "glasses IMU: left to the system")
        }
    }

    override val status: String
        get() = if (device.isRunning) "Glasses cameras${if (imuActive) " + IMU" else ""}" else "Glasses cameras not running"

    override fun startRecording() {
        val usb = device.find()
        if (!host.playing || usb == null || !device.isRunning) {
            host.showStatus("Recording needs the glasses cameras running")
            return
        }
        host.beginRecording("Recording glasses cameras", usb = true) {
            GlassesRecording.start(host.activity, host.settings, usb, imuActive, host.recordingInfo())
                .also { rec -> calibration?.let(rec::onCalibration) }
        }
    }

    override fun streamLost(recording: ActiveRecording): String? =
        if (recording is GlassesRecording && !device.isRunning) "the glasses stream stopped" else null

    override fun alignBlocker(s: StereoSettings): String? = when {
        !device.isRunning -> "Auto-align needs the glasses cameras running"
        !s.glassesRectify -> "Auto-align works on the rectified view: turn on Settings → Rectify glasses cameras"
        else -> null
    }

    override fun captureLayout(s: StereoSettings) = CaptureLayout(
        listOf(CapturePreview.Tile("cam0 left"), CapturePreview.Tile("cam1 right")),
        if (s.glassesImu) "Glasses IMU (imu0)" else "Glasses IMU left to the system (Settings → Glasses IMU)",
        imuOn = s.glassesImu,
    )

    companion object {
        private const val TAG = "StereoView"
        private const val IMAGE_W = 480
        private const val IMAGE_H = 640

        fun exposureOf(s: StereoSettings) = GlassesDevice.Exposure(s.glassesManualExposure, s.glassesExposure100us, s.glassesGain)

        /** Taking over the IMU, or how many of its reads to queue, is fixed when the stream starts. */
        fun needsRestart(s: StereoSettings, previous: StereoSettings) =
            s.glassesImu != previous.glassesImu || (s.glassesImu && s.buffers.glassesImuS != previous.buffers.glassesImuS)
    }
}
