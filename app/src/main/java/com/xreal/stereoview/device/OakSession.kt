package com.xreal.stereoview.device

import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.preview.CapturePreview
import com.xreal.stereoview.record.ActiveRecording
import com.xreal.stereoview.record.OakRecording
import com.xreal.stereoview.record.OakRgbFormat
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.StereoSettings
import org.json.JSONObject

/** A Luxonis OAK over USB (depthai): the mono pair, the color camera and the IMU. */
class OakSession(host: SessionHost) : DeviceSession(host) {
    override val source = CameraSource.OAK
    val device = OakDevice(host.activity.applicationContext, Listener())
    private var statsTime = 0L

    override val isRunning: Boolean get() = device.isRunning

    private inner class Listener : OakDevice.Listener {
        override fun onStatus(message: String) {
            Log.i(TAG, message)
            host.showStatus(message)
        }

        override fun onError(message: String) {
            Log.w(TAG, message)
            host.showError(message)
        }

        override fun onStarted(info: JSONObject) {
            Log.i(TAG, "OAK started: ${info.optString("product_name")} ${info.optString("usb_speed")}")
            host.refreshStatus()
            if (info.optString("usb_speed") == "USB2") {
                host.showStatus("OAK on USB2: high resolutions and frame rates may drop frames. A USB3 port and cable help.")
            }
        }

        override fun onStereoFrame(frame: OakDevice.StereoFrame) {
            host.renderer?.oakFrame = frame
            (host.recording as? OakRecording)?.onStereoFrame(frame)
            host.capture?.let {
                it.gray(0, frame.left.image, frame.left.width, frame.left.height)
                it.gray(1, frame.right.image, frame.right.width, frame.right.height)
            }
        }

        override fun onRgb(frame: OakDevice.Rgb) {
            (host.recording as? OakRecording)?.onRgb(frame)
            val capture = host.capture ?: return
            if (frame.raw) capture.nv12(2, frame.data, frame.width, frame.height) else capture.jpeg(2, frame.data)
        }

        override fun onImu(samples: List<OakDevice.ImuSample>) {
            (host.recording as? OakRecording)?.onImu(samples)
            host.capture?.let { c ->
                // Each sensor at its own rate: packets repeat the slower one's last sample (skipped by timestamp).
                for (x in samples) {
                    c.gyro.add(x.gyroNs, x.gx.toFloat(), x.gy.toFloat(), x.gz.toFloat())
                    c.accel.add(x.accelNs, x.ax.toFloat(), x.ay.toFloat(), x.az.toFloat())
                }
            }
        }
    }

    override fun start() {
        statsTime = SystemClock.elapsedRealtime()
        host.controls.setOakMode(true)
        device.start(configOf(host.settings))
        host.refreshStatus()
    }

    override fun stop() {
        if (host.recording is OakRecording) host.stopRecording("Recording stopped: OAK camera stopped")
        device.stop()
        host.renderer?.oakFrame = null
    }

    override fun restartReason(s: StereoSettings, previous: StereoSettings): String? =
        if (needsRestart(s, previous)) "Recording stopped: OAK settings changed" else null

    override fun applyLive(s: StereoSettings, previous: StereoSettings) {
        for (i in s.oakExposures.indices) {
            if (s.oakExposures[i] != previous.oakExposures.getOrNull(i)) device.setExposure(i, s.oakExposures[i])
        }
    }

    override fun hud(stats: StereoRenderer.RenderStats): String {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - statsTime) / 1000.0).coerceAtLeast(1e-3)
        statsTime = now
        val o = device.takeStats()
        val info = device.info
        return buildString {
            appendLine(
                "OAK: %.1f pairs/s  mono %.1f/s  color %.1f/s  IMU %.0f Hz  draw %.1f fps".format(
                    o.pairs / seconds, o.monoFrames / seconds, o.rgbFrames / seconds, o.imuSamples / seconds, stats.drawFps,
                ),
            )
            append("lost mono frames ${o.droppedSequences}  ${info?.optString("usb_speed") ?: ""} ${info?.optString("product_name") ?: ""}")
        }
    }

    override val status: String
        get() {
            val info = device.info
            return if (device.isRunning) {
                "OAK ${info?.optString("product_name")?.ifEmpty { null } ?: "camera"} (${info?.optString("usb_speed") ?: "?"})"
            } else {
                "OAK camera not running"
            }
        }

    override fun startRecording() {
        val info = device.info
        if (!host.playing || !device.isRunning || info == null) {
            host.showStatus("Recording needs the OAK camera running")
            return
        }
        host.beginRecording("Recording OAK cameras", usb = true) {
            OakRecording.start(host.activity, host.settings, info, host.recordingInfo())
        }
    }

    override fun streamLost(recording: ActiveRecording): String? =
        if (recording is OakRecording && !device.isRunning) "the OAK camera stopped" else null

    override fun alignBlocker(s: StereoSettings): String =
        "Auto-align works on rectified views, which the OAK preview doesn't have yet; use Settings → Eye alignment"

    override fun captureLayout(s: StereoSettings) = CaptureLayout(
        listOfNotNull(
            CapturePreview.Tile("cam0 left"), CapturePreview.Tile("cam1 right"),
            if (s.oakRgbHeight > 0) CapturePreview.Tile("cam2 color") else null,
        ),
        if (s.oakImuRateHz > 0) "OAK IMU (imu0), raw" else "OAK IMU off (Settings → OAK IMU)",
        imuOn = s.oakImuRateHz > 0,
    )

    companion object {
        private const val TAG = "StereoView"

        /** The camera pipeline for settings [s]. */
        fun configOf(s: StereoSettings) = OakDevice.Config(
            s.oakMonoHeight, s.oakFps, s.oakRgbHeight, s.oakJpegQuality, s.oakImuRateHz, s.oakUsb3, s.oakExposures,
            linkFrames = (s.buffers.oakLinkS * maxOf(s.oakFps, s.oakRgbFps)).coerceAtLeast(OakDevice.MIN_LINK_FRAMES),
            imuPackets = (s.buffers.oakImuS * (s.oakImuRateHz + OakDevice.accelRateFor(s.oakImuRateHz))).coerceAtLeast(OakDevice.MIN_IMU_PACKETS),
            rgbFps = s.oakRgbFps,
            rgbRaw = s.oakRgbFormat != OakRgbFormat.JPEG,
        )

        /** Any pipeline change but the exposures (which the camera takes live) needs a restart. */
        fun needsRestart(s: StereoSettings, previous: StereoSettings) =
            configOf(s).copy(exposures = previous.oakExposures) != configOf(previous)
    }
}
