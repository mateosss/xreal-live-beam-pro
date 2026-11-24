package com.xreal.stereoview.ui

import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.xreal.stereoview.R
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.record.BasaltCalib
import com.xreal.stereoview.record.ImuStream
import com.xreal.stereoview.record.Recording
import java.io.File

/** Settings a [SettingsSheet] shows and edits. */
interface SettingsTarget {
    val value: StereoSettings
    fun update(transform: (StereoSettings) -> StereoSettings)
}

/** The live settings: changes apply at once and persist. */
class LiveSettings(private val viewModel: SettingsViewModel) : SettingsTarget {
    override val value: StereoSettings get() = viewModel.settings.value
    override fun update(transform: (StereoSettings) -> StereoSettings) = viewModel.update(transform)
}

/** A copy being edited (a profile in the editor); [onChange] runs after every change. */
class DraftSettings(initial: StereoSettings, private val onChange: () -> Unit) : SettingsTarget {
    override var value: StereoSettings = initial
        private set
    override fun update(transform: (StereoSettings) -> StereoSettings) {
        value = transform(value)
        onChange()
    }
}

/**
 * All settings in one sheet, grouped by what they affect. The Capture and Recording groups
 * are stored in profiles; the rest describes this device and stays when switching profiles.
 * Only the rows that apply to the selected camera source are shown. Rows show their current
 * values and update live.
 */
class SettingsSheet(
    private val context: Context,
    private val viewModel: SettingsViewModel,
    private val host: Host,
    /** The settings shown and edited: the live ones, or a profile's draft in the editor. */
    private val target: SettingsTarget = LiveSettings(viewModel),
    /** Profile editor: only the profile's own settings, with Save and Cancel. */
    private val editor: Editor? = null,
) {
    /** What the profile editor saves into. */
    class Editor(val profileName: String, val onSave: (StereoSettings) -> Unit)

    interface Host {
        val rig: StereoRig?
        val glassesControls: GlassesDevice.CameraControls?
        fun isRecording(): Boolean
        fun cameraPairOptions(): List<Pair<String, String>>
        fun setStatus(text: CharSequence)
        fun showProfiles()
        fun probeUsb()
        fun showRecordings()

        /** Lets the user pick a file; [onPicked] gets its uri and display name (or nothing if cancelled). */
        fun pickFile(onPicked: (android.net.Uri, String?) -> Unit)
    }

    private var dialog: BottomSheetDialog? = null
    private var content: LinearLayout? = null
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(24))
        }
        val dialog = BottomSheetDialog(context).apply {
            setContentView(NestedScrollView(context).apply { addView(content) })
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnDismissListener {
                this@SettingsSheet.dialog = null
                this@SettingsSheet.content = null
            }
        }
        this.content = content
        this.dialog = dialog
        refresh()
        dialog.show()
    }

    /**
     * Rebuilds the rows from the current settings, if the sheet is open. Layout:
     * the active profile; the current device's profile settings (camera, recording, view);
     * that device's setup, kept across profiles; display settings shared by all devices;
     * recordings and tools.
     */
    fun refresh() {
        val content = content ?: return
        content.removeAllViews()
        val s = target.value
        val recording = host.isRecording()
        val device = s.cameraSource

        if (editor != null) {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(4))
            }
            bar.addView(TextView(context).apply {
                text = "Edit “${editor.profileName}”"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            bar.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Cancel"
                setOnClickListener { dialog?.dismiss() }
            })
            bar.addView(MaterialButton(context).apply {
                text = "Save"
                setOnClickListener {
                    editor.onSave(target.value)
                    dialog?.dismiss()
                }
            })
            content.addView(bar)
            profileRows(content, s, recording, device, "${device.label}", null)
            row(
                content, "Convergence, depth, exposure",
                "Set these with the panel's sliders while streaming, then Save to profile",
            ) {}
            return
        }
        profileHeader(content, s)
        profileRows(content, s, recording, device, device.label, "saved in the profile")

        section(content, "${device.shortLabel} setup", "this device, kept across profiles")
        setupRows(content, s, recording, device)

        section(content, "Display", "all devices")
        displayRows(content, s, recording)

        section(content, "Data", null)
        row(content, "Recordings", recordingsSummary()) { host.showRecordings() }
    }

    /** The settings a profile stores for [device]. */
    private fun profileRows(content: LinearLayout, s: StereoSettings, recording: Boolean, device: CameraSource, title: String, note: String?) {
        section(content, title, note)
        subsection(content, "Camera")
        when (device) {
            CameraSource.GLASSES -> glassesCaptureRows(content, s, recording)
            CameraSource.BEAM_PRO -> beamProCaptureRows(content, s, recording)
            CameraSource.OAK -> oakCaptureRows(content, s, recording)
        }
        subsection(content, "Recording")
        recordingRows(content, s, recording)
        subsection(content, "View")
        row(content, "Phone preview (portrait)", "${s.previewStyle.label}: ${s.previewStyle.description}") {
            choose("Phone preview in portrait", PreviewStyle.entries.map { "${it.label}: ${it.description}" }, s.previewStyle.ordinal) { i ->
                target.update { it.copy(previewStyle = PreviewStyle.entries[i]) }
            }
        }
        row(content, "Scale", s.scaleMode.label) {
            choose("Scale", ScaleMode.entries.map { it.label }, s.scaleMode.ordinal) { i ->
                target.update { it.copy(scaleMode = ScaleMode.entries[i]) }
            }
        }
        val portraitModes = PORTRAIT_SCALE_MODES
        row(content, "Scale in portrait (phone preview)", portraitMode(s.portraitScaleMode).label) {
            choose("Scale in portrait (Raw: unrectified images, whole)", portraitModes.map { it.label }, portraitModes.indexOf(portraitMode(s.portraitScaleMode))) { i ->
                target.update { it.copy(portraitScaleMode = portraitModes[i]) }
            }
        }
    }

    /**
     * The Basalt calibration recordings of [device] carry (calibration.bslt.json): the factory
     * one, or a file imported here.
     */
    private fun calibrationRow(content: LinearLayout, device: CameraSource, recording: Boolean) {
        val custom = BasaltCalib.Custom.name(context, device)
        row(content, "Calibration (Basalt)", if (custom != null) "Custom: $custom" else "Factory calibration, exported with each recording", locked = recording) {
            val options = listOf(
                "Import a Basalt calibration file (calibration.bslt.json in every ${device.shortLabel} recording)",
                "Use the factory calibration" + if (custom == null) " (current)" else "",
            )
            choose("Calibration for ${device.shortLabel} recordings", options, if (custom != null) 0 else 1) { i ->
                if (i == 1) {
                    BasaltCalib.Custom.clear(context, device)
                    host.setStatus("${device.shortLabel} recordings will carry the factory calibration")
                    refresh()
                } else {
                    host.pickFile { uri, name ->
                        val message = runCatching { BasaltCalib.Custom.import(context, device, uri, name) }
                            .fold({ "Calibration for ${device.shortLabel} recordings: ${name ?: "file"} ($it)" }, { "⚠ Not imported: ${it.message}" })
                        host.setStatus(message)
                        refresh()
                    }
                }
            }
        }
    }

    private fun setupRows(content: LinearLayout, s: StereoSettings, recording: Boolean, device: CameraSource) {
        if (device == CameraSource.BEAM_PRO) beamProSetupRows(content, s, recording)
        row(content, "Eye alignment", "pitch %+.2f°, roll %+.2f° (Align measures it)".format(s.alignPitchDeg, s.alignRollDeg)) {
            showAlignmentDialog()
        }
        row(content, "Recording buffers", buffersSummary(s, device), locked = recording) { showBuffersDialog() }
        calibrationRow(content, device, recording)
        if (device == CameraSource.GLASSES) {
            row(content, "Glasses USB probe", "Check whether the app can reach the glasses' cameras and IMU") { host.probeUsb() }
        }
    }

    private fun recordingRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        val glassesSource = s.cameraSource == CameraSource.GLASSES
        if (s.cameraSource == CameraSource.OAK) {
            val monoVideo = s.oakImageFormat == com.xreal.stereoview.record.GrayImageFormat.H264
            grayFormatRows(content, s, recording, s.oakImageFormat, "Mono images") { f -> target.update { it.copy(oakImageFormat = f) } }
            if (monoVideo) {
                bitrateRow(content, s, recording, "Mono video bitrate", s.oakVideoBitrateMbps, listOf(4, 8, 12, 20, 40)) { v -> target.update { it.copy(oakVideoBitrateMbps = v) } }
            }
            val rgbFormats = com.xreal.stereoview.record.OakRgbFormat.entries
            val rgbVideo = s.oakRgbHeight > 0 && s.oakRgbFormat == com.xreal.stereoview.record.OakRgbFormat.H264
            if (s.oakRgbHeight > 0) {
                row(content, "Color images", s.oakRgbFormat.label, locked = recording) {
                    choose("Color images (cam2)", rgbFormats.map { it.label }, rgbFormats.indexOf(s.oakRgbFormat)) { i -> target.update { it.copy(oakRgbFormat = rgbFormats[i]) } }
                }
                if (s.oakRgbFormat == com.xreal.stereoview.record.OakRgbFormat.JPEG) {
                    row(content, "Color JPEG quality", "${s.oakJpegQuality} (encoded on the camera)", locked = recording) {
                        val values = listOf(98, 95, 90, 85, 75)
                        choose("Color JPEG quality", values.map { "$it" }, values.indexOf(s.oakJpegQuality)) { i -> target.update { it.copy(oakJpegQuality = values[i]) } }
                    }
                }
                if (rgbVideo) {
                    bitrateRow(content, s, recording, "Color video bitrate", s.oakRgbVideoBitrateMbps, listOf(8, 12, 20, 40, 60)) { v -> target.update { it.copy(oakRgbVideoBitrateMbps = v) } }
                }
            }
            if (monoVideo || rgbVideo) videoRows(content, s, recording, null, emptyList(), true) {}
            row(
                content, "Layout",
                "Mono pair in cam0, cam1" + (if (s.oakRgbHeight > 0) ", color in cam2" else "") +
                    "; OAK IMU as imu0; EuRoC layout on the OAK's clock",
            ) {}
        } else if (glassesSource) {
            grayFormatRows(content, s, recording, s.glassesImageFormat, "Images") { f -> target.update { it.copy(glassesImageFormat = f) } }
            if (s.glassesImageFormat == com.xreal.stereoview.record.GrayImageFormat.H264) {
                videoRows(content, s, recording, s.glassesVideoBitrateMbps, listOf(2, 4, 6, 10, 16), true) { v -> target.update { it.copy(glassesVideoBitrateMbps = v) } }
            }
            row(content, "Layout", "Raw 480×640 grayscale per camera at 30 fps, EuRoC layout, timestamps on the glasses' clock") {}
        } else {
            row(content, "Images", s.recordView.label, locked = recording) {
                choose("Recorded images", RecordView.entries.map { it.label }, s.recordView.ordinal) { i ->
                    target.update { it.copy(recordView = RecordView.entries[i]) }
                }
            }
            row(content, "Format", s.recordFormat.label, locked = recording) {
                choose("Format", RecordFormat.entries.map { it.label }, s.recordFormat.ordinal) { i ->
                    target.update { it.copy(recordFormat = RecordFormat.entries[i]) }
                }
            }
            if (s.recordFormat.mime != null) {
                videoRows(content, s, recording, s.recordBitrateMbps, listOf(10, 20, 40, 60, 100), s.recordFormat == RecordFormat.H264) { v ->
                    target.update { it.copy(recordBitrateMbps = v) }
                }
            }
        }
        // Only recordings of the phone's own cameras include its IMU (it doesn't move with other devices).
        if (s.cameraSource != CameraSource.BEAM_PRO) return
        val imuPrefix = "IMU"
        val sensors = context.getSystemService(SensorManager::class.java)
        row(content, "$imuPrefix rate", "${s.imuRate.label}\n${imuRateNote(sensors, s.imuRate)}", locked = recording) {
            val labels = ImuRate.entries.map { "${it.label}\n${imuRateNote(sensors, it)}" }
            choose("$imuPrefix sampling", labels, s.imuRate.ordinal) { i -> target.update { it.copy(imuRate = ImuRate.entries[i]) } }
        }
        val streams = ImuStream.parse(s.imuStreams)
        row(content, "$imuPrefix streams", streams.joinToString { it.file.replace('_', ' ') }.ifEmpty { "none" }, locked = recording) {
            chooseImuStreams(sensors, streams)
        }
    }

    /** Bitrate, rate control, keyframe interval and (H.264) profile of video recordings. */
    private fun videoRows(
        content: LinearLayout, s: StereoSettings, recording: Boolean, bitrate: Int?, choices: List<Int>, h264: Boolean, setBitrate: (Int) -> Unit,
    ) {
        val modes = com.xreal.stereoview.record.BitrateMode.entries
        row(content, "Rate control", s.videoBitrateMode.label.substringBefore(":"), locked = recording) {
            choose("Rate control", modes.map { it.label }, modes.indexOf(s.videoBitrateMode)) { i -> target.update { it.copy(videoBitrateMode = modes[i]) } }
        }
        if (bitrate != null) bitrateRow(content, s, recording, "Video bitrate", bitrate, choices, setBitrate)
        val intervals = listOf(0.5f, 1f, 2f, 5f)
        row(content, "Keyframe interval", "every %s s".format(s.videoKeyframeIntervalS.toString().removeSuffix(".0")), locked = recording) {
            val labels = listOf("0.5 s (fast seeking, robust)", "1 s", "2 s", "5 s (smallest files)")
            choose("Keyframe interval", labels, intervals.indexOf(s.videoKeyframeIntervalS)) { i -> target.update { it.copy(videoKeyframeIntervalS = intervals[i]) } }
        }
        if (h264) {
            val profiles = com.xreal.stereoview.record.H264Profile.entries
            row(content, "H.264 profile", s.videoProfile.label, locked = recording) {
                choose("H.264 profile", profiles.map { it.label }, profiles.indexOf(s.videoProfile)) { i -> target.update { it.copy(videoProfile = profiles[i]) } }
            }
        }
        val encoders = listOf("Hardware (the device's encoder)", "Software (Android's own encoder: same on every device, uses the CPU)")
        row(content, "Encoder", if (s.videoSoftwareEncoder) "software" else "hardware", locked = recording) {
            choose("Encoder", encoders, if (s.videoSoftwareEncoder) 1 else 0) { i -> target.update { it.copy(videoSoftwareEncoder = i == 1) } }
        }
    }

    /** Bitrate of one video stream (hidden in constant-quality mode, which has none). */
    private fun bitrateRow(
        content: LinearLayout, s: StereoSettings, recording: Boolean, title: String, bitrate: Int, choices: List<Int>, set: (Int) -> Unit,
    ) {
        if (s.videoBitrateMode == com.xreal.stereoview.record.BitrateMode.CQ) return
        row(content, title, "$bitrate Mbit/s per camera" + if (s.videoBitrateMode == com.xreal.stereoview.record.BitrateMode.VBR) " (average)" else "", locked = recording) {
            choose("$title per camera", choices.map { "$it Mbit/s" }, choices.indexOf(bitrate)) { i -> set(choices[i]) }
        }
    }

    /** Format of recorded grayscale images, and the JPEG quality when it applies. */
    private fun grayFormatRows(
        content: LinearLayout, s: StereoSettings, recording: Boolean, format: com.xreal.stereoview.record.GrayImageFormat,
        title: String, set: (com.xreal.stereoview.record.GrayImageFormat) -> Unit,
    ) {
        val formats = com.xreal.stereoview.record.GrayImageFormat.entries
        row(content, title, format.label, locked = recording) {
            choose(title, formats.map { it.label }, formats.indexOf(format)) { i -> set(formats[i]) }
        }
        if (format == com.xreal.stereoview.record.GrayImageFormat.JPEG) {
            row(content, "JPEG quality", "${s.grayJpegQuality}", locked = recording) {
                val values = listOf(98, 95, 90, 85, 75)
                val labels = listOf("98 (near lossless)", "95", "90 (good for SLAM features)", "85", "75 (smallest)")
                choose("JPEG quality", labels, values.indexOf(s.grayJpegQuality)) { i -> target.update { it.copy(grayJpegQuality = values[i]) } }
            }
        }
    }

    private fun displayRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        row(content, "Glasses field of view", "%.0f° diagonal (used by 1:1 scale)".format(s.glassesFovDeg)) {
            val presets = listOf(
                45f to "45° — Air",
                46f to "46° — Air 2 / Air 2 Pro",
                50f to "50° — One",
                52f to "52° — Air 2 Ultra",
                57f to "57° — One Pro",
            )
            choose("Glasses field of view (diagonal)", presets.map { it.second }, presets.indexOfFirst { it.first == s.glassesFovDeg }) { i ->
                target.update { it.copy(glassesFovDeg = presets[i].first) }
            }
        }
        row(content, "SBS layout", s.sbsLayout.label) {
            choose("SBS layout", SbsLayout.entries.map { it.label }, s.sbsLayout.ordinal) { i ->
                target.update { it.copy(sbsLayout = SbsLayout.entries[i]) }
            }
        }
        row(content, "Output", s.outputMode.label, locked = recording) {
            choose("Output", OutputMode.entries.map { it.label }, s.outputMode.ordinal) { i ->
                target.update { it.copy(outputMode = OutputMode.entries[i]) }
            }
        }
        row(content, "Reset view adjustments", "Convergence, depth and brightness back to defaults") {
            val d = StereoSettings()
            target.update { it.copy(convergencePx = d.convergencePx, depth = d.depth, exposure = d.exposure) }
        }
    }

    private fun beamProCaptureRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        row(content, "Frame rate", "${s.targetFps} fps", locked = recording) {
            val rig = host.rig
            val values = rig?.availableFps ?: listOf(30)
            val labels = values.map { fps -> if (rig != null && fps !in rig.autoExposureFps) "$fps fps (manual exposure only)" else "$fps fps" }
            // Say what limits the list: the AE ranges, manual control, and the streams' speed.
            val fastest = rig?.maxFps?.maxByOrNull { it.value }
            val why = if (rig == null) "" else buildString {
                append("\nAuto exposure up to ${rig.autoExposureFps.maxOrNull() ?: "?"} fps")
                append(if (rig.manualSensor) "; manual exposure supported" else "; manual exposure not supported, so no faster rates")
                fastest?.let { append("; fastest stream ${it.key} at %.0f fps".format(it.value)) }
            }
            choose("Frame rate (lower resolution if needed)$why", labels, values.indexOf(s.targetFps)) { i ->
                target.update { it.copy(targetFps = values[i]) }
            }
        }
        row(content, "Max resolution", "${s.maxResolution}p", locked = recording) {
            val values = listOf(1080, 720, 480)
            choose("Max resolution", values.map { "${it}p" }, values.indexOf(s.maxResolution)) { i ->
                target.update { it.copy(maxResolution = values[i]) }
            }
        }
        row(content, "Focus", s.focusMode.label, locked = recording) {
            choose("Focus", FocusMode.entries.map { it.label }, s.focusMode.ordinal) { i ->
                target.update { it.copy(focusMode = FocusMode.entries[i]) }
            }
        }
        row(content, "Stabilization", s.stabilization.label) {
            choose("Stabilization", Stabilization.entries.map { it.label }, s.stabilization.ordinal) { i ->
                target.update { it.copy(stabilization = Stabilization.entries[i]) }
            }
        }
        row(content, "Sync wait", "${s.syncThresholdMs} ms for the partner frame") {
            val values = listOf(8, 16, 33, 50, 100)
            choose("Wait for partner frame", values.map { "$it ms" }, values.indexOf(s.syncThresholdMs)) { i ->
                target.update { it.copy(syncThresholdMs = values[i]) }
            }
        }
    }

    private fun oakCaptureRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        row(content, "Mono cameras", "${if (s.oakMonoHeight == 400) "640×400" else "1280×${s.oakMonoHeight}"} grayscale, global shutter", locked = recording) {
            val values = listOf(800, 720, 400)
            val labels = listOf("1280×800 (full sensor)", "1280×720", "640×400 (binned: least bandwidth, the default)")
            choose("Mono resolution", labels, values.indexOf(s.oakMonoHeight)) { i -> target.update { it.copy(oakMonoHeight = values[i]) } }
        }
        row(content, "Frame rate", "${s.oakFps} fps (mono pair, synchronized)", locked = recording) {
            val values = listOf(10, 15, 20, 30, 45, 60)
            choose("Mono frame rate (USB2 limits high rates at full resolution)", values.map { "$it fps" }, values.indexOf(s.oakFps)) { i ->
                target.update { it.copy(oakFps = values[i]) }
            }
        }
        val rgbStream = if (s.oakRgbFormat == com.xreal.stereoview.record.OakRgbFormat.JPEG) "JPEG ${s.oakJpegQuality} from the camera" else "raw NV12 over USB (USB3 needed)"
        row(content, "Color camera", if (s.oakRgbHeight > 0) "1280×${s.oakRgbHeight}, $rgbStream" else "Off", locked = recording) {
            val values = listOf(800, 720, 0)
            val labels = listOf("1280×800", "1280×720", "Off (mono pair only)")
            choose("Color camera (its format: Recording → Color images)", labels, values.indexOf(s.oakRgbHeight)) { i ->
                target.update { it.copy(oakRgbHeight = values[i]) }
            }
        }
        if (s.oakRgbHeight > 0) {
            val rgb = if (s.oakRgbFps > 0) s.oakRgbFps else s.oakFps
            row(content, "Color frame rate", "$rgb fps · ${rgbFpsNote(s.oakFps, rgb)}", locked = recording) {
                val values = listOf(5, 10, 15, 20, 30, 45, 60).filter { it == s.oakFps || maxOf(it, s.oakFps) <= 60 }
                choose(
                    "Color frame rate (mono at ${s.oakFps} fps)",
                    values.map { "$it fps · ${rgbFpsNote(s.oakFps, it)}" }, values.indexOf(rgb),
                ) { i -> target.update { it.copy(oakRgbFps = if (values[i] == it.oakFps) 0 else values[i]) } }
            }
        }
        val maxGyro = com.xreal.stereoview.oak.OakDevice.MAX_GYRO_HZ
        val maxAccel = com.xreal.stereoview.oak.OakDevice.MAX_ACCEL_HZ
        val imuLabel = when {
            s.oakImuRateHz <= 0 -> "Off"
            s.oakImuRateHz >= maxGyro -> "Maximum: gyroscope $maxGyro Hz, accelerometer $maxAccel Hz (raw)"
            else -> "${s.oakImuRateHz} Hz raw accelerometer + gyroscope"
        }
        row(content, "OAK IMU", imuLabel, locked = recording) {
            val values = listOf(maxGyro, 200, 100, 0)
            val labels = listOf(
                "Maximum (default): gyroscope $maxGyro Hz, accelerometer $maxAccel Hz on the BNO086; a BMI270 runs at its fastest supported rates",
                "200 Hz both", "100 Hz both", "Off",
            )
            choose("OAK IMU rate", labels, values.indexOf(s.oakImuRateHz)) { i ->
                target.update { it.copy(oakImuRateHz = values[i]) }
            }
        }
        row(content, "USB speed", if (s.oakUsb3) "USB3 when available" else "USB2 only", locked = recording) {
            choose("USB speed", listOf("USB3 when available", "USB2 only (for unreliable cables or hubs)"), if (s.oakUsb3) 0 else 1) { i ->
                target.update { it.copy(oakUsb3 = i == 0) }
            }
        }
        row(content, "Exposure", "Per camera with the panel's controls: its first button picks All, cam0, cam1 or cam2; " +
            com.xreal.stereoview.oak.OakDevice.Exposure.summary(s.oakExposures, s.oakRgbHeight > 0)) {}
    }

    /** How a color rate [rgb] combines with the mono rate [mono] (see OakDevice.FpsPlan). */
    private fun rgbFpsNote(mono: Int, rgb: Int): String {
        val plan = com.xreal.stereoview.oak.OakDevice.FpsPlan.of(mono, rgb)
        return when {
            mono == rgb -> "same as mono, synchronized"
            !plan.synchronized -> "not a multiple of $mono: free-running, not synchronized"
            rgb < mono -> "synchronized: 1 in ${plan.rgbEvery} frames, aligned with a mono pair"
            else -> "synchronized: sensors run at $rgb fps, 1 in ${plan.monoEvery} mono pairs kept (max mono exposure 1/$rgb s)"
        }
    }

    private fun glassesCaptureRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        row(content, "Frame rate", "30 fps per camera (fixed by the glasses; left and right alternate at 60 Hz)") {}
        row(
            content, "Glasses IMU",
            if (s.glassesImu) "Taken over and recorded as imu0 (XREAL head tracking stops until replug)" else "Left to the system",
            locked = recording,
        ) {
            choose(
                "Glasses IMU",
                listOf(
                    "Leave to the system (head tracking keeps working)",
                    "Take over and record it, with the factory calibration (head tracking stops until the glasses are replugged)",
                ),
                if (s.glassesImu) 1 else 0,
            ) { i -> target.update { it.copy(glassesImu = i == 1) } }
        }
        row(
            content, "Rectify the stereo view",
            (if (s.glassesRectify) "On: undistorted and aligned (reference calibration)" else "Off: images as streamed") +
                ". Only the passthrough stereo view; the capture view and recordings stay raw",
        ) {
            choose(
                "Glasses stereo view (passthrough style and landscape)",
                listOf(
                    "Rectified: fisheye undistorted, eyes aligned (Monado's reference calibration), for comfortable stereo viewing",
                    "As streamed (distorted)",
                ),
                if (s.glassesRectify) 0 else 1,
            ) { i -> target.update { it.copy(glassesRectify = i == 0) } }
        }
        val c = host.glassesControls
        val exposure = when {
            c == null -> "Shown once the glasses cameras stream"
            !c.any -> "The glasses offer no exposure or gain controls over USB; they run their own auto exposure"
            else -> listOfNotNull(
                c.exposure?.let { "exposure %.1f–%.1f ms".format(it.min / 10.0, it.max / 10.0) },
                c.gain?.let { "gain ${it.min}–${it.max}" },
            ).joinToString(", ") + " (adjust in the panel)"
        }
        row(content, "Exposure controls", exposure) {
            c?.let { Dialogs.info(context, "Glasses camera controls", "${it.report}\n\nLast exposure request:\n${it.lastApplied}") }
        }
    }

    private fun beamProSetupRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        row(content, "Camera pair", s.cameraPair.ifEmpty { "Automatic" }, locked = recording) {
            val options = listOf("" to "Auto (best pair, with fallbacks)") + host.cameraPairOptions()
            val labels = options.map { (key, description) -> if (key.isEmpty()) description else "$key\n$description" }
            choose("Camera pair", labels, options.indexOfFirst { it.first == s.cameraPair }) { i ->
                target.update { it.copy(cameraPair = options[i].first) }
            }
        }
        row(content, "Image rotation", if (s.rotationOverride < 0) "Auto (from sensor orientation)" else "${s.rotationOverride}°") {
            val values = listOf(-1, 0, 90, 180, 270)
            choose("Image rotation", values.map { if (it < 0) "Auto (from sensor orientation)" else "$it°" }, values.indexOf(s.rotationOverride)) { i ->
                target.update { it.copy(rotationOverride = values[i]) }
            }
        }
        row(content, "Lens calibration", if (s.useFactoryCalibration) "Factory (lens poses from the camera)" else "Sensor orientation only") {
            choose(
                "Lens calibration",
                listOf("Factory (lens poses from the camera)", "Sensor orientation only"),
                if (s.useFactoryCalibration) 0 else 1,
            ) { i -> target.update { it.copy(useFactoryCalibration = i == 0) } }
        }
    }

    private fun profileHeader(parent: LinearLayout, s: StereoSettings) {
        val state = viewModel.profiles.value
        val active = state.activeProfile
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        bar.addView(TextView(context).apply {
            text = when {
                active == null -> "No profile selected"
                active.differsFrom(s) -> "Profile: ${active.name}  •  edited"
                else -> "Profile: ${active.name}"
            }
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (active != null && active.differsFrom(s)) {
            bar.addView(MaterialButton(context).apply {
                text = "Save to profile"
                setOnClickListener {
                    viewModel.saveProfile(active.name)
                    host.setStatus("Saved profile ${active.name}")
                }
            })
        }
        bar.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Profiles…"
            setOnClickListener { host.showProfiles() }
        })
        parent.addView(bar)
    }

    private fun subsection(parent: LinearLayout, title: String) {
        parent.addView(TextView(context).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.08f
            setPadding(dp(16), dp(10), dp(16), dp(2))
        })
    }

    private fun section(parent: LinearLayout, title: String, note: String?) {
        parent.addView(TextView(context).apply {
            text = if (note != null) "$title  ·  $note" else title
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(18), dp(16), dp(4))
        })
    }

    /** A tappable two-line row; [locked] rows explain that a recording is running instead. */
    private fun row(parent: LinearLayout, title: String, value: String, locked: Boolean = false, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            alpha = if (locked) 0.45f else 1f
            setOnClickListener {
                if (locked) host.setStatus("Stop the recording to change $title") else onClick()
            }
        }
        row.addView(TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            // Narrow (portrait) screens give the value more room.
            layoutParams = LinearLayout.LayoutParams(
                dp(if (context.resources.configuration.screenWidthDp < 600) 140 else 220), LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        row.addView(TextView(context).apply {
            text = value
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
        })
        parent.addView(row)
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onChosen: (Int) -> Unit) =
        Dialogs.choose(context, title, labels, checked, onChosen)

    /** What [rate] gives on this device for the gyroscope and accelerometer. */
    private fun imuRateNote(sensors: SensorManager, rate: ImuRate): String {
        val main = listOf(Sensor.TYPE_GYROSCOPE to "gyro", Sensor.TYPE_ACCELEROMETER to "accel")
            .mapNotNull { (type, name) -> sensors.getDefaultSensor(type)?.let { it to name } }
        if (main.isEmpty()) return "no IMU found"
        return main.joinToString { (sensor, name) ->
            if (rate.directRate > 0) {
                if (sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) &&
                    sensor.highestDirectReportRateLevel >= rate.directRate
                ) "$name: supported" else "$name: unsupported, uses the fastest listener"
            } else {
                val max = if (sensor.minDelay > 0) 1e6 / sensor.minDelay else 0.0
                val hz = if (rate.periodUs == 0) max else minOf(max, 1e6 / rate.periodUs)
                "$name: %.0f Hz".format(hz)
            }
        }
    }

    private fun chooseImuStreams(sensors: SensorManager, selected: List<ImuStream>) {
        val all = ImuStream.entries
        val checked = BooleanArray(all.size) { all[it] in selected }
        val labels = all.map { stream ->
            val sensor = sensors.getDefaultSensor(stream.type)
            if (sensor == null) "${stream.label} (not on this device)" else "${stream.label}\n${sensor.name}"
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("IMU streams to record")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val names = all.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.name }
                target.update { it.copy(imuStreams = names) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun recordingDirs(): List<File> =
        Recording.root(context).listFiles()?.filter { it.isDirectory || it.name.endsWith(".zip") }.orEmpty()

    private fun recordingsSummary(): String {
        val count = recordingDirs().size
        return "%d saved · %.1f GB free".format(count, Recording.root(context).let { if (it.exists()) it.usableSpace else context.getExternalFilesDir(null)?.usableSpace ?: 0L } / 1e9)
    }

    /** One recording buffer: its slider range and what a second of it costs in memory. */
    private class BufferSpec(
        val group: String,
        val label: String,
        val max: Int,
        val get: (BufferSettings) -> Int,
        val set: (BufferSettings, Int) -> BufferSettings,
        /** Frames (or reads) per second per stream, bytes each, number of streams. */
        val rate: Int,
        val bytes: Long,
        val streams: Int,
        /** Shares the camera memory cap ([com.xreal.stereoview.record.RecordingBuffers.memoryCapBytes]). */
        val capped: Boolean,
    )

    private fun bufferSpecs(s: StereoSettings): List<BufferSpec> {
        val oakMono = (if (s.oakMonoHeight == 400) 640L else 1280L) * s.oakMonoHeight
        val phoneH = s.maxResolution.toLong()
        val phoneFrame = phoneH * 16 / 9 * phoneH * 4
        return listOf(
            BufferSpec("Glasses", "Cameras (encoder or image writers)", 60, { it.glassesCameraS }, { b, v -> b.copy(glassesCameraS = v) },
                30, 480L * 640, 2, true),
            BufferSpec("Glasses", "IMU (USB reads queued)", 30, { it.glassesImuS }, { b, v -> b.copy(glassesImuS = v) },
                GlassesDevice.IMU_RATE_HZ, 512, 1, false),
            BufferSpec("OAK", "Mono cameras (encoder or image writers)", 60, { it.oakCameraS }, { b, v -> b.copy(oakCameraS = v) },
                s.oakFps, oakMono, 2, true),
            BufferSpec("OAK", "USB link (frames not yet picked up)", 10, { it.oakLinkS }, { b, v -> b.copy(oakLinkS = v) },
                s.oakFps, oakMono, 2, false), // color JPEGs are small next to the mono frames
            BufferSpec("OAK", "IMU (packets not yet picked up)", 30, { it.oakImuS }, { b, v -> b.copy(oakImuS = v) },
                (s.oakImuRateHz + com.xreal.stereoview.oak.OakDevice.accelRateFor(s.oakImuRateHz)).coerceAtLeast(1), 200, 1, false),
            BufferSpec("Phone cameras", "JPEG/PNG writers (video needs none)", 30, { it.phoneCameraS }, { b, v -> b.copy(phoneCameraS = v) },
                s.targetFps, phoneFrame, 2, true),
        )
    }

    /** "8 s · ≈147 MB" for [spec] at [seconds]; notes when the memory cap shortens it. */
    private fun bufferText(spec: BufferSpec, seconds: Int): String {
        if (!spec.capped) return "$seconds s · ≈%s".format(formatMb(seconds.toLong() * spec.rate * spec.bytes * spec.streams / 1e6))
        val frames = com.xreal.stereoview.record.RecordingBuffers.frames(seconds, spec.rate, spec.bytes, spec.streams, min = 1)
        val mb = formatMb(frames * spec.bytes * spec.streams / 1e6)
        return if (frames < seconds.toLong() * spec.rate) {
            "$seconds s · capped to %.1f s (≈%s)".format(com.xreal.stereoview.record.RecordingBuffers.seconds(frames, spec.rate), mb)
        } else {
            "$seconds s · ≈$mb"
        }
    }

    private fun formatMb(mb: Double) = if (mb < 10) "%.1f MB".format(mb) else "%.0f MB".format(mb)

    private fun buffersSummary(s: StereoSettings, device: CameraSource): String {
        val b = s.buffers
        return when (device) {
            CameraSource.GLASSES -> "cameras ${b.glassesCameraS} s, IMU ${b.glassesImuS} s"
            CameraSource.OAK -> "cameras ${b.oakCameraS} s, USB ${b.oakLinkS} s, IMU ${b.oakImuS} s"
            CameraSource.BEAM_PRO -> "image writers ${b.phoneCameraS} s"
        } + " (all devices: tap)"
    }

    /** Sliders for every recording buffer, a Defaults button and a short guide. */
    private fun showBuffersDialog() {
        val s = target.value
        var buffers = s.buffers
        val specs = bufferSpecs(s)
        val scroll = NestedScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        scroll.addView(layout)
        fun small(text: String, top: Int = 0) = TextView(context).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
            setPadding(0, dp(top), 0, 0)
        }
        val capMb = com.xreal.stereoview.record.RecordingBuffers.memoryCapBytes() / 1e6
        val sliders = ArrayList<Pair<BufferSpec, Slider>>()
        var group = ""
        for (spec in specs) {
            if (spec.group != group) {
                group = spec.group
                layout.addView(TextView(context).apply {
                    text = group
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(14), 0, 0)
                })
            }
            val label = TextView(context).apply { setPadding(0, dp(6), 0, 0) }
            fun show(v: Int) { label.text = "${spec.label}: ${bufferText(spec, v)}" }
            val slider = Slider(context).apply {
                valueFrom = 1f
                valueTo = spec.max.toFloat()
                stepSize = 1f
                value = spec.get(buffers).coerceIn(1, spec.max).toFloat()
                labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                isTickVisible = false
                addOnChangeListener { _, v, _ ->
                    buffers = spec.set(buffers, v.toInt())
                    show(v.toInt())
                }
            }
            show(spec.get(buffers))
            layout.addView(label)
            layout.addView(slider)
            sliders += spec to slider
        }
        val d = BufferSettings()
        layout.addView(small(
            "Buffers absorb short stalls (CPU spikes, throttling, slow storage); data is dropped only when one is full.\n" +
                "• Recommended: 5–10 s for cameras, 5 s for IMUs.\n" +
                "• No buffer saves an encoder that is slower on average: if encoder_backlog_peak_frames reaches the capacity, " +
                "use the hardware encoder or a lower bitrate.\n" +
                "• Camera buffers share %.0f MB (60%% of the app's memory limit), so Android won't kill the recording.\n"
                    .format(capMb) +
                "• Defaults: glasses ${d.glassesCameraS} s / IMU ${d.glassesImuS} s, OAK ${d.oakCameraS} s / USB ${d.oakLinkS} s / IMU ${d.oakImuS} s, phone ${d.phoneCameraS} s.",
            top = 16,
        ))
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Recording buffers")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ -> target.update { it.copy(buffers = buffers) } }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Defaults", null)
            .show()
        // Defaults resets the sliders without closing; OK saves.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            for ((spec, slider) in sliders) slider.value = spec.get(d).coerceIn(1, spec.max).toFloat()
        }
    }

    /** Sliders for the vertical and rotational alignment between the eyes. */
    private fun showAlignmentDialog() {
        val range = StereoSettings.ALIGN_RANGE_DEG
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun slider(label: String, value: Float, onChange: (Float) -> Unit) {
            val text = TextView(context).apply { this.text = "$label %+.2f°".format(value) }
            val slider = Slider(context).apply {
                valueFrom = -range
                valueTo = range
                this.value = value.coerceIn(-range, range)
                labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                isTickVisible = false
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    text.text = "$label %+.2f°".format(v)
                    onChange(v)
                }
            }
            layout.addView(text)
            layout.addView(slider)
        }
        val s = target.value
        slider("Vertical (pitch)", s.alignPitchDeg) { d -> target.update { it.withAlignment(d, it.alignRollDeg) } }
        slider("Rotation (roll)", s.alignRollDeg) { d -> target.update { it.withAlignment(it.alignPitchDeg, d) } }

        MaterialAlertDialogBuilder(context)
            .setTitle("Eye alignment")
            .setMessage("Line up a distant horizontal edge so it is not doubled vertically. Align does this from the image.")
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Reset") { _, _ -> target.update { it.withAlignment(0f, 0f) } }
            .show()
    }
}
