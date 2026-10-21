package com.xreal.stereoview.ui

import android.util.Range
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.xreal.stereoview.R
import com.xreal.stereoview.databinding.OverlayControlsBinding
import kotlin.math.roundToInt

/**
 * The control panel on the Beam Pro touchscreen. When the stereo view goes to a separate
 * glasses display, the panel stays on the phone only; when the glasses mirror the phone, the
 * panel is drawn over the view and can be hidden with a tap.
 */
class ControlsOverlay(
    private val binding: OverlayControlsBinding,
    private val viewModel: SettingsViewModel,
    private val actions: Actions,
) {
    interface Actions {
        fun onPlayPause()
        fun onShowInfo()
        fun onAutoAlign()

        /** Selectable camera pairs as (key, description), best first. */
        fun cameraPairOptions(): List<Pair<String, String>>
    }

    private val context = binding.root.context
    private var exposureRange = Range(0, 0)

    init {
        binding.playButton.setOnClickListener { actions.onPlayPause() }
        binding.infoButton.setOnClickListener { actions.onShowInfo() }
        binding.alignButton.setOnClickListener { actions.onAutoAlign() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        binding.swapButton.setOnClickListener { viewModel.update { it.copy(swapEyes = !it.swapEyes) } }
        binding.hudButton.setOnClickListener { viewModel.update { it.copy(showHud = !it.showHud) } }
        binding.scaleButton.setOnClickListener {
            viewModel.update {
                val modes = ScaleMode.entries
                it.copy(scaleMode = modes[(it.scaleMode.ordinal + 1) % modes.size])
            }
        }

        binding.convergenceSeek.max = (StereoSettings.CONVERGENCE_RANGE * 2).toInt()
        binding.convergenceSeek.onUserChange { progress ->
            viewModel.update { it.copy(convergencePx = progress - StereoSettings.CONVERGENCE_RANGE) }
        }
        binding.depthSeek.max = 100
        binding.depthSeek.onUserChange { progress ->
            val depth = StereoSettings.DEPTH_MIN + (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * progress / 100f
            viewModel.update { it.copy(depth = depth) }
        }
        binding.brightnessSeek.onUserChange { progress ->
            viewModel.update { it.copy(exposure = exposureRange.lower + progress) }
        }
    }

    fun render(s: StereoSettings) {
        binding.convergenceSeek.progress = (s.convergencePx + StereoSettings.CONVERGENCE_RANGE).roundToInt()
        binding.convergenceLabel.text = context.getString(R.string.convergence) + " %+.0f px".format(s.convergencePx)
        binding.depthSeek.progress =
            ((s.depth - StereoSettings.DEPTH_MIN) / (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * 100).roundToInt()
        binding.depthLabel.text = context.getString(R.string.depth) + " %.2f×".format(s.depth)
        binding.brightnessSeek.progress = s.exposure - exposureRange.lower
        binding.brightnessLabel.text = context.getString(R.string.brightness) + " %+d".format(s.exposure)
        binding.swapButton.text = if (s.swapEyes) "Swap L/R ✓" else context.getString(R.string.swap)
        binding.hudButton.text = if (s.showHud) "HUD ✓" else context.getString(R.string.hud)
        binding.scaleButton.text = s.scaleMode.label
    }

    fun setExposureRange(range: Range<Int>) {
        exposureRange = range
        binding.brightnessSeek.max = range.upper - range.lower
        binding.brightnessSeek.isEnabled = range.upper > range.lower
        render(viewModel.settings.value)
    }

    fun setPlaying(playing: Boolean) {
        binding.playButton.setText(if (playing) R.string.pause else R.string.play)
    }

    fun setStatus(text: CharSequence) {
        binding.statusText.text = text
    }

    private fun showMoreMenu() {
        val s = viewModel.settings.value
        val items = arrayOf(
            "Camera pair: ${s.cameraPair.ifEmpty { "auto" }}",
            "Output: ${s.outputMode.label}",
            "Image rotation: ${if (s.rotationOverride < 0) "auto" else "${s.rotationOverride}°"}",
            "Max resolution: ${s.maxResolution}p",
            "Sync wait: ${s.syncThresholdMs} ms",
            "Stabilization: ${s.stabilization.label}",
            "Glasses field of view: %.0f°".format(s.glassesFovDeg),
            "SBS layout: ${s.sbsLayout.label}",
            "Lens calibration: ${if (s.useFactoryCalibration) "factory" else "sensor orientation only"}",
            "Manual alignment: pitch %+.2f°, roll %+.2f°".format(s.alignPitchDeg, s.alignRollDeg),
            "Reset view adjustments",
        )
        AlertDialog.Builder(context)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val options = listOf("" to "Auto (best pair, with fallbacks)") + actions.cameraPairOptions()
                        val labels = options.map { (key, description) -> if (key.isEmpty()) description else "$key\n$description" }
                        choose("Camera pair", labels, options.indexOfFirst { it.first == s.cameraPair }) { i ->
                            viewModel.update { it.copy(cameraPair = options[i].first) }
                        }
                    }
                    1 -> choose("Output", OutputMode.entries.map { it.label }, s.outputMode.ordinal) { i ->
                        viewModel.update { it.copy(outputMode = OutputMode.entries[i]) }
                    }
                    2 -> {
                        val values = listOf(-1, 0, 90, 180, 270)
                        choose("Image rotation", values.map { if (it < 0) "Auto" else "$it°" }, values.indexOf(s.rotationOverride)) { i ->
                            viewModel.update { it.copy(rotationOverride = values[i]) }
                        }
                    }
                    3 -> {
                        val values = listOf(1080, 720, 480)
                        choose("Max resolution", values.map { "${it}p" }, values.indexOf(s.maxResolution)) { i ->
                            viewModel.update { it.copy(maxResolution = values[i]) }
                        }
                    }
                    4 -> {
                        val values = listOf(16, 33, 50, 100)
                        choose("Wait for partner frame", values.map { "$it ms" }, values.indexOf(s.syncThresholdMs)) { i ->
                            viewModel.update { it.copy(syncThresholdMs = values[i]) }
                        }
                    }
                    5 -> choose("Stabilization", Stabilization.entries.map { it.label }, s.stabilization.ordinal) { i ->
                        viewModel.update { it.copy(stabilization = Stabilization.entries[i]) }
                    }
                    6 -> {
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
                    7 -> choose("SBS layout", SbsLayout.entries.map { it.label }, s.sbsLayout.ordinal) { i ->
                        viewModel.update { it.copy(sbsLayout = SbsLayout.entries[i]) }
                    }
                    8 -> choose(
                        "Lens calibration",
                        listOf("Factory (lens poses from the camera)", "Sensor orientation only"),
                        if (s.useFactoryCalibration) 0 else 1,
                    ) { i -> viewModel.update { it.copy(useFactoryCalibration = i == 0) } }
                    9 -> showAlignmentDialog()
                    10 -> viewModel.update { it.copy(convergencePx = 0f, depth = 1f, exposure = 0) }
                }
            }
            .show()
    }

    /** Sliders for the vertical and rotational alignment between the eyes. */
    private fun showAlignmentDialog() {
        val range = StereoSettings.ALIGN_RANGE_DEG
        val steps = 300
        fun toProgress(deg: Float) = ((deg + range) / (2 * range) * steps).roundToInt()
        fun toDegrees(progress: Int) = progress.toFloat() / steps * 2 * range - range

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        fun slider(label: String, value: Float, onChange: (Float) -> Unit) {
            val text = TextView(context).apply { this.text = "$label %+.2f°".format(value) }
            val seek = SeekBar(context).apply {
                max = steps
                progress = toProgress(value)
                onUserChange { p ->
                    val deg = toDegrees(p)
                    text.text = "$label %+.2f°".format(deg)
                    onChange(deg)
                }
            }
            layout.addView(text)
            layout.addView(seek)
        }
        val s = viewModel.settings.value
        slider("Vertical (pitch)", s.alignPitchDeg) { d -> viewModel.update { it.copy(alignPitchDeg = d) } }
        slider("Rotation (roll)", s.alignRollDeg) { d -> viewModel.update { it.copy(alignRollDeg = d) } }

        AlertDialog.Builder(context)
            .setTitle("Eye alignment")
            .setMessage("Line up a distant horizontal edge so it is not doubled vertically. Auto-align does this from the image.")
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Reset") { _, _ -> viewModel.update { it.copy(alignPitchDeg = 0f, alignRollDeg = 0f) } }
            .show()
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onChosen(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun SeekBar.onUserChange(onChange: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
}
