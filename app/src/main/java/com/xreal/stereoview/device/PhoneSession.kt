package com.xreal.stereoview.device

import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import com.xreal.stereoview.camera.CameraController
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.camera.CameraTarget
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.preview.CapturePreview
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.FocusMode
import com.xreal.stereoview.ui.StereoSettings

/**
 * The phone's (Beam Pro's) own rear cameras through Camera2, rendered (and recorded) on the GL
 * thread from [surfaces], plus the phone IMU for the capture view.
 */
class PhoneSession(
    host: SessionHost,
    /** The renderer's camera surfaces (left, right), once it has made them. */
    private val surfaces: () -> Pair<SurfaceTexture, SurfaceTexture>?,
    private val displayRotation: () -> Int,
) : DeviceSession(host),
    CameraController.Listener {
    override val source = CameraSource.BEAM_PRO
    val cameraManager: CameraManager = host.activity.getSystemService(CameraManager::class.java)
    val camera = CameraController(host.activity, this)

    /** The camera pair in use, and its stream size once streaming. Main thread. */
    var rig: StereoRig? = null
        private set
    var streamSize: Size? = null
        private set

    private var captureStats = ""
    private var sensors: SensorEventListener? = null

    override val isRunning: Boolean get() = streamSize != null

    override fun start() {
        val (left, right) = surfaces() ?: return
        val s = host.settings
        val rig = CameraUtils.selectRig(cameraManager, displayRotation(), s.maxResolution, s.cameraPair, s.targetFps)
        if (rig == null) {
            onCameraError("No rear camera found")
            return
        }
        this.rig = rig
        host.controls.setRig(rig)
        camera.start(rig, left, right, exposureOf(s))
    }

    /** Pauses the cameras; the rig and the last frame stay (play resumes them). */
    override fun stop() = camera.pause()

    override fun onOtherSourceStarting() {
        camera.pause()
        rig = null
        streamSize = null
        host.renderer?.cameras = null
    }

    /** Reopening with new settings needs no pause first. */
    override fun restart() = start()

    override fun shutdown() {
        camera.stopBlocking()
        streamSize = null
    }

    override fun release() = camera.release()

    override fun onRecordingStopped() {
        camera.resultListener = null
    }

    override fun onCameraStatus(message: String) {
        Log.i(TAG, message)
        host.showStatus(message)
    }

    override fun onCameraStreaming(rig: StereoRig, size: Size) {
        host.activity.runOnUiThread {
            Log.i(TAG, "Streaming ${rig.description} at $size")
            this.rig = rig
            streamSize = size
            host.controls.setRig(rig)
            host.renderer?.cameras = cameraModels(rig, size)
            host.refreshStatus()
        }
    }

    override fun onCameraError(message: String) {
        Log.w(TAG, message)
        host.showError(message)
    }

    override fun onCaptureStats(exposureTimeNs: Long, iso: Int, frameDurationNs: Long) {
        val text = "sensor: exposure 1/%d s, ISO %d, frame %.1f ms".format(
            if (exposureTimeNs > 0) (1e9 / exposureTimeNs).toInt() else 0, iso, frameDurationNs / 1e6,
        )
        host.activity.runOnUiThread { captureStats = text }
    }

    /** Geometric models of the rig's two streams; a single camera yields the same model twice. */
    private fun cameraModels(rig: StereoRig, size: Size): StereoRenderer.Cameras? = try {
        fun model(target: CameraTarget) = CameraModel.from(
            target.label, cameraManager.getCameraCharacteristics(target.physicalId ?: target.deviceId), size, rig.undistortInShader,
        )
        val left = model(rig.left)
        val right = if (rig.right == rig.left) left else model(rig.right)
        StereoRenderer.Cameras(left, right, displayRotation())
    } catch (e: Exception) {
        Log.w(TAG, "Cannot build camera models", e)
        null
    }

    override fun restartReason(s: StereoSettings, previous: StereoSettings): String? =
        if (needsRestart(s, previous)) "Recording stopped: camera settings changed" else null

    override fun applyLive(s: StereoSettings, previous: StereoSettings) {
        if (exposureOf(s) != exposureOf(previous)) camera.setExposure(exposureOf(s))
    }

    override fun hud(stats: StereoRenderer.RenderStats): String {
        val sync = stats.sync
        val size = streamSize
        return buildString {
            appendLine("L %.1f fps  R %.1f fps  draw %.1f fps (%.2f ms)".format(stats.leftFps, stats.rightFps, stats.drawFps, stats.drawMs))
            appendLine("L/R Δ mean %.2f ms  max %.2f ms  over threshold %d/%d".format(sync.meanDeltaMs, sync.maxDeltaMs, sync.unsyncedPairs, sync.pairs))
            appendLine(
                "stream ${size ?: "-"} (max %.0f fps)  output %d×%d".format(
                    size?.let { rig?.maxFps?.get(it) } ?: 0.0, stats.outputSize.width, stats.outputSize.height,
                ),
            )
            append(captureStats)
        }
    }

    override val status: String
        get() {
            val rig = rig
            val size = streamSize
            return if (rig == null || size == null) "Starting cameras…" else "${rig.description} @ $size"
        }

    override val statusWarning: String
        get() = if (rig?.baselineVertical == true) "\n⚠ The cameras are stacked vertically in this orientation: turn the device to landscape for stereo depth." else ""

    override fun startRecording() {
        val renderer = host.renderer
        val rig = rig
        val size = streamSize
        val cams = renderer?.cameras
        if (renderer == null || rig == null || size == null || cams == null || !host.playing) {
            host.showStatus("Recording needs the cameras running")
            return
        }
        val rec = host.beginRecording("Recording", usb = false) {
            Recording.start(host.activity, host.settings, rig, size, cams, cameraManager, host.recordingInfo())
        } ?: return
        camera.resultListener = rec
        renderer.startRecording(rec)
    }

    override fun alignBlocker(s: StereoSettings): String? = if (streamSize == null) "Auto-align needs the cameras running" else null

    override fun captureLayout(s: StereoSettings) =
        CaptureLayout(listOf(CapturePreview.Tile("phone cameras: left / right", external = true)), "Phone IMU", imuOn = true)

    override val drawsInCaptureArea: Boolean get() = true

    /** The phone's cameras reach only the renderer; the capture view gets the phone IMU here. */
    override fun onCaptureShown(view: CapturePreview) {
        onCaptureHidden()
        val sm = host.activity.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val plot = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) view.gyro else view.accel
                plot.add(e.timestamp, e.values[0], e.values[1], e.values[2])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        for (type in listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER)) {
            sm.getDefaultSensor(type)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        sensors = listener
    }

    override fun onCaptureHidden() {
        sensors?.let { host.activity.getSystemService(SensorManager::class.java).unregisterListener(it) }
        sensors = null
    }

    companion object {
        private const val TAG = "StereoView"

        fun exposureOf(s: StereoSettings) =
            CameraController.Exposure(s.exposure, s.manualExposure, s.exposureTimeNs, s.iso, s.focusMode == FocusMode.INFINITY)

        /** The stream size, camera pair and frame rate are chosen when the cameras open. */
        fun needsRestart(s: StereoSettings, previous: StereoSettings) =
            s.maxResolution != previous.maxResolution || s.cameraPair != previous.cameraPair || s.targetFps != previous.targetFps
    }
}
