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
import com.xreal.stereoview.record.ImuStream
import com.xreal.stereoview.record.Recording
import java.io.File

/**
 * All settings in one sheet, grouped by what they affect. The Capture and Recording groups
 * are stored in profiles; the rest describes this device and stays when switching profiles.
 * Rows show their current values and update live.
 */
class SettingsSheet(private val context: Context, private val viewModel: SettingsViewModel, private val host: Host) {
    interface Host {
        val rig: StereoRig?
        fun isRecording(): Boolean
        fun cameraPairOptions(): List<Pair<String, String>>
        fun setStatus(text: CharSequence)
        fun showProfiles()
        fun probeUsb()
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

    /** Rebuilds the rows from the current settings, if the sheet is open. */
    fun refresh() {
        val content = content ?: return
        content.removeAllViews()
        val s = viewModel.settings.value
        val recording = host.isRecording()

        profileHeader(content, s)

        section(content, "Capture", "saved in profiles")
        row(content, "Camera source", s.cameraSource.label, locked = recording) {
            choose("Camera source (one at a time)", CameraSource.entries.map { it.label }, s.cameraSource.ordinal) { i ->
                viewModel.update { it.copy(cameraSource = CameraSource.entries[i]) }
            }
        }
        val glassesSource = s.cameraSource == CameraSource.GLASSES
        if (glassesSource) {
            row(
                content, "Glasses IMU",
                if (s.glassesImu) "Taken over and recorded (XREAL head tracking stops until replug)" else "Left to the system",
                locked = recording,
            ) {
                choose(
                    "Glasses IMU",
                    listOf(
                        "Leave to the system (head tracking keeps working)",
                        "Take over and record it, with the factory calibration (head tracking stops until the glasses are replugged)",
                    ),
                    if (s.glassesImu) 1 else 0,
                ) { i -> viewModel.update { it.copy(glassesImu = i == 1) } }
            }
        }
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
                viewModel.update { it.copy(targetFps = values[i]) }
            }
        }
        row(content, "Max resolution", "${s.maxResolution}p", locked = recording) {
            val values = listOf(1080, 720, 480)
            choose("Max resolution", values.map { "${it}p" }, values.indexOf(s.maxResolution)) { i ->
                viewModel.update { it.copy(maxResolution = values[i]) }
            }
        }
        row(content, "Focus", s.focusMode.label, locked = recording) {
            choose("Focus", FocusMode.entries.map { it.label }, s.focusMode.ordinal) { i ->
                viewModel.update { it.copy(focusMode = FocusMode.entries[i]) }
            }
        }
        row(content, "Stabilization", s.stabilization.label) {
            choose("Stabilization", Stabilization.entries.map { it.label }, s.stabilization.ordinal) { i ->
                viewModel.update { it.copy(stabilization = Stabilization.entries[i]) }
            }
        }
        row(content, "Scale", s.scaleMode.label) {
            choose("Scale", ScaleMode.entries.map { it.label }, s.scaleMode.ordinal) { i ->
                viewModel.update { it.copy(scaleMode = ScaleMode.entries[i]) }
            }
        }
        row(content, "Sync wait", "${s.syncThresholdMs} ms for the partner frame") {
            val values = listOf(8, 16, 33, 50, 100)
            choose("Wait for partner frame", values.map { "$it ms" }, values.indexOf(s.syncThresholdMs)) { i ->
                viewModel.update { it.copy(syncThresholdMs = values[i]) }
            }
        }

        section(content, "Recording", "saved in profiles")
        if (glassesSource) {
            row(content, "Glasses images", "Raw 480×640 grayscale PNG per frame (lossless), 30 fps per camera; the options below apply to the Beam Pro cameras") {}
        }
        row(content, "Images", s.recordView.label, locked = recording) {
            choose("Recorded images", RecordView.entries.map { it.label }, s.recordView.ordinal) { i ->
                viewModel.update { it.copy(recordView = RecordView.entries[i]) }
            }
        }
        row(content, "Format", s.recordFormat.label, locked = recording) {
            choose("Format", RecordFormat.entries.map { it.label }, s.recordFormat.ordinal) { i ->
                viewModel.update { it.copy(recordFormat = RecordFormat.entries[i]) }
            }
        }
        if (s.recordFormat.mime != null) {
            row(content, "Video bitrate", "${s.recordBitrateMbps} Mbit/s per camera", locked = recording) {
                val values = listOf(10, 20, 40, 60, 100)
                choose("Video bitrate per camera", values.map { "$it Mbit/s" }, values.indexOf(s.recordBitrateMbps)) { i ->
                    viewModel.update { it.copy(recordBitrateMbps = values[i]) }
                }
            }
        }
        val sensors = context.getSystemService(SensorManager::class.java)
        row(content, "IMU rate", "${s.imuRate.label}\n${imuRateNote(sensors, s.imuRate)}", locked = recording) {
            val labels = ImuRate.entries.map { "${it.label}\n${imuRateNote(sensors, it)}" }
            choose("IMU sampling", labels, s.imuRate.ordinal) { i -> viewModel.update { it.copy(imuRate = ImuRate.entries[i]) } }
        }
        val streams = ImuStream.parse(s.imuStreams)
        row(content, "IMU streams", streams.joinToString { it.file.replace('_', ' ') }.ifEmpty { "none" }, locked = recording) {
            chooseImuStreams(sensors, streams)
        }
        row(content, "Recordings", recordingsSummary()) { showRecordings() }

        section(content, "Glasses & output", null)
        row(content, "Glasses field of view", "%.0f° diagonal (used by 1:1 scale)".format(s.glassesFovDeg)) {
            val presets = listOf(
                45f to "45° — Air",
                46f to "46° — Air 2 / Air 2 Pro",
                50f to "50° — One",
                52f to "52° — Air 2 Ultra",
                57f to "57° — One Pro",
            )
            choose("Glasses field of view (diagonal)", presets.map { it.second }, presets.indexOfFirst { it.first == s.glassesFovDeg }) { i ->
                viewModel.update { it.copy(glassesFovDeg = presets[i].first) }
            }
        }
        row(content, "SBS layout", s.sbsLayout.label) {
            choose("SBS layout", SbsLayout.entries.map { it.label }, s.sbsLayout.ordinal) { i ->
                viewModel.update { it.copy(sbsLayout = SbsLayout.entries[i]) }
            }
        }
        row(content, "Output", s.outputMode.label, locked = recording) {
            choose("Output", OutputMode.entries.map { it.label }, s.outputMode.ordinal) { i ->
                viewModel.update { it.copy(outputMode = OutputMode.entries[i]) }
            }
        }
        row(content, "Glasses USB probe", "Check whether the app can reach the glasses' cameras and IMU") { host.probeUsb() }

        section(content, "Camera setup", "this device")
        row(content, "Camera pair", s.cameraPair.ifEmpty { "Automatic" }, locked = recording) {
            val options = listOf("" to "Auto (best pair, with fallbacks)") + host.cameraPairOptions()
            val labels = options.map { (key, description) -> if (key.isEmpty()) description else "$key\n$description" }
            choose("Camera pair", labels, options.indexOfFirst { it.first == s.cameraPair }) { i ->
                viewModel.update { it.copy(cameraPair = options[i].first) }
            }
        }
        row(content, "Image rotation", if (s.rotationOverride < 0) "Auto (from sensor orientation)" else "${s.rotationOverride}°") {
            val values = listOf(-1, 0, 90, 180, 270)
            choose("Image rotation", values.map { if (it < 0) "Auto (from sensor orientation)" else "$it°" }, values.indexOf(s.rotationOverride)) { i ->
                viewModel.update { it.copy(rotationOverride = values[i]) }
            }
        }
        row(content, "Lens calibration", if (s.useFactoryCalibration) "Factory (lens poses from the camera)" else "Sensor orientation only") {
            choose(
                "Lens calibration",
                listOf("Factory (lens poses from the camera)", "Sensor orientation only"),
                if (s.useFactoryCalibration) 0 else 1,
            ) { i -> viewModel.update { it.copy(useFactoryCalibration = i == 0) } }
        }
        row(content, "Eye alignment", "pitch %+.2f°, roll %+.2f° (Align measures it)".format(s.alignPitchDeg, s.alignRollDeg)) {
            showAlignmentDialog()
        }
        row(content, "Reset view adjustments", "Convergence, depth and brightness back to defaults") {
            val d = StereoSettings()
            viewModel.update { it.copy(convergencePx = d.convergencePx, depth = d.depth, exposure = d.exposure) }
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
            layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT)
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
                viewModel.update { it.copy(imuStreams = names) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun recordingDirs(): List<File> =
        Recording.root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()

    private fun recordingsSummary(): String {
        val count = recordingDirs().size
        return "%d saved · %.1f GB free".format(count, Recording.root(context).let { if (it.exists()) it.usableSpace else context.getExternalFilesDir(null)?.usableSpace ?: 0L } / 1e9)
    }

    /** Lists saved recordings, newest first, with their size; tap one to delete it. */
    private fun showRecordings() {
        val root = Recording.root(context)
        val dirs = recordingDirs()
        val message = "Copy them over USB (Android/data/${context.packageName}/files/recordings) or with\n" +
            "adb pull /sdcard/Android/data/${context.packageName}/files/recordings"
        if (dirs.isEmpty()) {
            MaterialAlertDialogBuilder(context).setTitle("No recordings yet").setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = dirs.map { "${it.name}   %.0f MB".format(sizeOf(it) / 1e6) }
        MaterialAlertDialogBuilder(context)
            .setTitle("Recordings (%.1f GB free)".format(root.usableSpace / 1e9))
            .setItems(labels.toTypedArray()) { _, which ->
                val dir = dirs[which]
                MaterialAlertDialogBuilder(context)
                    .setTitle(dir.name)
                    .setMessage("$message\n\n${dir.path}")
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton("Delete") { _, _ ->
                        Dialogs.confirm(context, "Delete ${dir.name}?", "Delete") {
                            dir.deleteRecursively()
                            host.setStatus("Deleted ${dir.name}")
                            refresh()
                        }
                    }
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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
        val s = viewModel.settings.value
        slider("Vertical (pitch)", s.alignPitchDeg) { d -> viewModel.update { it.copy(alignPitchDeg = d) } }
        slider("Rotation (roll)", s.alignRollDeg) { d -> viewModel.update { it.copy(alignRollDeg = d) } }

        MaterialAlertDialogBuilder(context)
            .setTitle("Eye alignment")
            .setMessage("Line up a distant horizontal edge so it is not doubled vertically. Align does this from the image.")
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Reset") { _, _ -> viewModel.update { it.copy(alignPitchDeg = 0f, alignRollDeg = 0f) } }
            .show()
    }
}
