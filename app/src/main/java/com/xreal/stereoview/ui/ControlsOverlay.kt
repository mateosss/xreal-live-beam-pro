package com.xreal.stereoview.ui

import android.util.Range
import android.widget.SeekBar
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
    }

    private val context = binding.root.context
    private var exposureRange = Range(0, 0)

    init {
        binding.playButton.setOnClickListener { actions.onPlayPause() }
        binding.infoButton.setOnClickListener { actions.onShowInfo() }
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
            "Output: ${s.outputMode.label}",
            "Image rotation: ${if (s.rotationOverride < 0) "auto" else "${s.rotationOverride}°"}",
            "Max resolution: ${s.maxResolution}p",
            "Sync wait: ${s.syncThresholdMs} ms",
            "Reset view adjustments",
        )
        AlertDialog.Builder(context)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> choose("Output", OutputMode.entries.map { it.label }, s.outputMode.ordinal) { i ->
                        viewModel.update { it.copy(outputMode = OutputMode.entries[i]) }
                    }
                    1 -> {
                        val values = listOf(-1, 0, 90, 180, 270)
                        choose("Image rotation", values.map { if (it < 0) "Auto" else "$it°" }, values.indexOf(s.rotationOverride)) { i ->
                            viewModel.update { it.copy(rotationOverride = values[i]) }
                        }
                    }
                    2 -> {
                        val values = listOf(1080, 720, 480)
                        choose("Max resolution", values.map { "${it}p" }, values.indexOf(s.maxResolution)) { i ->
                            viewModel.update { it.copy(maxResolution = values[i]) }
                        }
                    }
                    3 -> {
                        val values = listOf(16, 33, 50, 100)
                        choose("Wait for partner frame", values.map { "$it ms" }, values.indexOf(s.syncThresholdMs)) { i ->
                            viewModel.update { it.copy(syncThresholdMs = values[i]) }
                        }
                    }
                    4 -> viewModel.update { it.copy(convergencePx = 0f, depth = 1f, exposure = 0) }
                }
            }
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
