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

    private fun setupRows(content: LinearLayout, s: StereoSettings, recording: Boolean, device: CameraSource) {
        if (device == CameraSource.BEAM_PRO) beamProSetupRows(content, s, recording)
        row(content, "Eye alignment", "pitch %+.2f°, roll %+.2f° (Align measures it)".format(s.alignPitchDeg, s.alignRollDeg)) {
            showAlignmentDialog()
        }
        if (device == CameraSource.GLASSES) {
            row(content, "Glasses USB probe", "Check whether the app can reach the glasses' cameras and IMU") { host.probeUsb() }
        }
    }

    private fun recordingRows(content: LinearLayout, s: StereoSettings, recording: Boolean) {
        val glassesSource = s.cameraSource == CameraSource.GLASSES
        if (s.cameraSource == CameraSource.OAK) {
            grayFormatRows(content, s, recording, s.oakImageFormat, "Mono images") { f -> target.update { it.copy(oakImageFormat = f) } }
            if (s.oakImageFormat == com.xreal.stereoview.record.GrayImageFormat.H264) {
                videoRows(content, s, recording, s.oakVideoBitrateMbps, listOf(4, 8, 12, 20, 40), true) { v -> target.update { it.copy(oakVideoBitrateMbps = v) } }
            }
            row(
                content, "Layout",
                "Mono pair in cam0, cam1" + (if (s.oakRgbHeight > 0) ", color as the camera's JPEGs in cam2" else "") +
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
        // With other devices the phone's IMU is an extra: it doesn't move with the cameras.
        val imuPrefix = if (s.cameraSource != CameraSource.BEAM_PRO) "Phone IMU (extra)" else "IMU"
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
        content: LinearLayout, s: StereoSettings, recording: Boolean, bitrate: Int, choices: List<Int>, h264: Boolean, setBitrate: (Int) -> Unit,
    ) {
        val modes = com.xreal.stereoview.record.BitrateMode.entries
        row(content, "Rate control", s.videoBitrateMode.label.substringBefore(":"), locked = recording) {
            choose("Rate control", modes.map { it.label }, modes.indexOf(s.videoBitrateMode)) { i -> target.update { it.copy(videoBitrateMode = modes[i]) } }
        }
        if (s.videoBitrateMode != com.xreal.stereoview.record.BitrateMode.CQ) {
            row(content, "Video bitrate", "$bitrate Mbit/s per camera" + if (s.videoBitrateMode == com.xreal.stereoview.record.BitrateMode.VBR) " (average)" else "", locked = recording) {
                choose("Video bitrate per camera", choices.map { "$it Mbit/s" }, choices.indexOf(bitrate)) { i -> setBitrate(choices[i]) }
            }
        }
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
            val labels = listOf("1280×800 (full sensor)", "1280×720", "640×400 (binned, least bandwidth)")
            choose("Mono resolution", labels, values.indexOf(s.oakMonoHeight)) { i -> target.update { it.copy(oakMonoHeight = values[i]) } }
        }
        row(content, "Frame rate", "${s.oakFps} fps (all cameras, synchronized)", locked = recording) {
            val values = listOf(15, 20, 30, 45, 60)
            choose("Frame rate (USB2 limits high rates at full resolution)", values.map { "$it fps" }, values.indexOf(s.oakFps)) { i ->
                target.update { it.copy(oakFps = values[i]) }
            }
        }
        row(content, "Color camera", if (s.oakRgbHeight > 0) "1280×${s.oakRgbHeight}, JPEG quality ${s.oakJpegQuality} (encoded on the camera)" else "Off", locked = recording) {
            val options = listOf(800 to 95, 800 to 85, 720 to 95, 0 to 95)
            val labels = listOf("1280×800, JPEG 95", "1280×800, JPEG 85 (smaller)", "1280×720, JPEG 95", "Off (mono pair only)")
            choose("Color camera", labels, options.indexOfFirst { it.first == s.oakRgbHeight && (it.first == 0 || it.second == s.oakJpegQuality) }) { i ->
                target.update { it.copy(oakRgbHeight = options[i].first, oakJpegQuality = options[i].second) }
            }
        }
        row(content, "OAK IMU", if (s.oakImuRateHz > 0) "${s.oakImuRateHz} Hz raw accelerometer + gyroscope" else "Off", locked = recording) {
            val values = listOf(100, 200, 250, 0)
            choose("OAK IMU rate", values.map { if (it == 0) "Off" else "$it Hz" }, values.indexOf(s.oakImuRateHz)) { i ->
                target.update { it.copy(oakImuRateHz = values[i]) }
            }
        }
        row(content, "USB speed", if (s.oakUsb3) "USB3 when available" else "USB2 only", locked = recording) {
            choose("USB speed", listOf("USB3 when available", "USB2 only (for unreliable cables or hubs)"), if (s.oakUsb3) 0 else 1) { i ->
                target.update { it.copy(oakUsb3 = i == 0) }
            }
        }
        row(content, "Exposure", "Auto or manual (shutter and ISO) with the panel's controls") {}
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
            content, "Rectify",
            if (s.glassesRectify) "On: undistorted and aligned (reference calibration); preview only" else "Off: images as streamed",
        ) {
            choose(
                "Glasses camera view",
                listOf(
                    "Rectified: fisheye undistorted, eyes aligned (Monado's reference calibration); recordings stay raw",
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
