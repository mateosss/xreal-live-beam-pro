package com.xreal.stereoview.ui

import android.util.Range
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.xreal.stereoview.R
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.databinding.OverlayControlsBinding
import kotlin.math.exp
import kotlin.math.ln
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
    private var rig: StereoRig? = null

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
        binding.shutterSeek.onUserChange { progress ->
            viewModel.update {
                val (min, max) = shutterRange(it)
                it.copy(exposureTimeNs = fromLog(progress / 1000.0, min.toDouble(), max.toDouble()).toLong())
            }
        }
        binding.isoSeek.onUserChange { progress ->
            val (min, max) = isoRange()
            viewModel.update { it.copy(iso = fromLog(progress / 1000.0, min.toDouble(), max.toDouble()).roundToInt()) }
        }
        binding.exposureModeButton.setOnClickListener {
            if (rig?.manualSensor == false) {
                setStatus("This camera does not support manual exposure")
            } else {
                viewModel.update { it.copy(manualExposure = !it.manualExposure) }
            }
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

        val manual = s.manualExposure && rig?.manualSensor != false
        binding.exposureModeButton.text = if (manual) "Exp: manual" else "Exp: auto"
        binding.brightnessRow.visibility = if (manual) View.GONE else View.VISIBLE
        binding.shutterRow.visibility = if (manual) View.VISIBLE else View.GONE
        binding.isoRow.visibility = if (manual) View.VISIBLE else View.GONE
        val (tMin, tMax) = shutterRange(s)
        val shutter = s.exposureTimeNs.coerceIn(tMin, tMax)
        binding.shutterSeek.progress = (toLog(shutter.toDouble(), tMin.toDouble(), tMax.toDouble()) * 1000).roundToInt()
        binding.shutterLabel.text = "Shutter 1/%d s".format((1e9 / shutter).roundToInt())
        val (isoMin, isoMax) = isoRange()
        binding.isoSeek.progress = (toLog(s.iso.toDouble().coerceIn(isoMin.toDouble(), isoMax.toDouble()), isoMin.toDouble(), isoMax.toDouble()) * 1000).roundToInt()
        binding.isoLabel.text = "ISO ${s.iso.coerceIn(isoMin, isoMax)}"
        binding.swapButton.text = if (s.swapEyes) "Swap L/R ✓" else context.getString(R.string.swap)
        binding.hudButton.text = if (s.showHud) "HUD ✓" else context.getString(R.string.hud)
        binding.scaleButton.text = s.scaleMode.label
    }

    /** Adapts the exposure controls and frame-rate options to the streaming rig. */
    fun setRig(rig: StereoRig) {
        this.rig = rig
        exposureRange = rig.exposureRange
        binding.brightnessSeek.max = exposureRange.upper - exposureRange.lower
        binding.brightnessSeek.isEnabled = exposureRange.upper > exposureRange.lower
        render(viewModel.settings.value)
    }

    /** Shortest and longest selectable exposure: down to 1/20000 s, up to one frame. */
    private fun shutterRange(s: StereoSettings): Pair<Long, Long> {
        val range = rig?.exposureTimeRange
        val min = maxOf(range?.lower ?: 50_000L, 50_000L)
        val max = minOf(range?.upper ?: 33_333_333L, 1_000_000_000L / s.targetFps)
        return min to maxOf(max, min + 1)
    }

    private fun isoRange(): Pair<Int, Int> {
        val range = rig?.sensitivityRange
        val min = range?.lower ?: 100
        return min to maxOf(range?.upper ?: 3200, min + 1)
    }

    /** Position 0..1 on a log scale between [min] and [max], and back. */
    private fun toLog(value: Double, min: Double, max: Double) = (ln(value / min) / ln(max / min)).coerceIn(0.0, 1.0)
    private fun fromLog(t: Double, min: Double, max: Double) = min * exp(t * ln(max / min))

    fun setPlaying(playing: Boolean) {
        binding.playButton.setText(if (playing) R.string.pause else R.string.play)
    }

    fun setStatus(text: CharSequence) {
        binding.statusText.text = text
    }

    private fun showMoreMenu() {
        val s = viewModel.settings.value
        val entries = listOf<Pair<String, () -> Unit>>(
            "Camera pair: ${s.cameraPair.ifEmpty { "auto" }}" to {
                val options = listOf("" to "Auto (best pair, with fallbacks)") + actions.cameraPairOptions()
                val labels = options.map { (key, description) -> if (key.isEmpty()) description else "$key\n$description" }
                choose("Camera pair", labels, options.indexOfFirst { it.first == s.cameraPair }) { i ->
                    viewModel.update { it.copy(cameraPair = options[i].first) }
                }
            },
            "Frame rate: ${s.targetFps} fps" to {
                val values = rig?.availableFps ?: listOf(30)
                val labels = values.map { fps ->
                    val note = if (rig?.let { fps !in it.autoExposureFps } == true) " (manual exposure only)" else ""
                    "$fps fps$note"
                }
                choose("Frame rate (lower resolution if needed)", labels, values.indexOf(s.targetFps)) { i ->
                    viewModel.update { it.copy(targetFps = values[i]) }
                }
            },
            "Output: ${s.outputMode.label}" to {
                choose("Output", OutputMode.entries.map { it.label }, s.outputMode.ordinal) { i ->
                    viewModel.update { it.copy(outputMode = OutputMode.entries[i]) }
                }
            },
            "Image rotation: ${if (s.rotationOverride < 0) "auto" else "${s.rotationOverride}°"}" to {
                val values = listOf(-1, 0, 90, 180, 270)
                choose("Image rotation", values.map { if (it < 0) "Auto (from sensor orientation)" else "$it°" }, values.indexOf(s.rotationOverride)) { i ->
                    viewModel.update { it.copy(rotationOverride = values[i]) }
                }
            },
            "Max resolution: ${s.maxResolution}p" to {
                val values = listOf(1080, 720, 480)
                choose("Max resolution", values.map { "${it}p" }, values.indexOf(s.maxResolution)) { i ->
                    viewModel.update { it.copy(maxResolution = values[i]) }
                }
            },
            "Sync wait: ${s.syncThresholdMs} ms" to {
                val values = listOf(8, 16, 33, 50, 100)
                choose("Wait for partner frame", values.map { "$it ms" }, values.indexOf(s.syncThresholdMs)) { i ->
                    viewModel.update { it.copy(syncThresholdMs = values[i]) }
                }
            },
            "Stabilization: ${s.stabilization.label}" to {
                choose("Stabilization", Stabilization.entries.map { it.label }, s.stabilization.ordinal) { i ->
                    viewModel.update { it.copy(stabilization = Stabilization.entries[i]) }
                }
            },
            "Glasses field of view: %.0f°".format(s.glassesFovDeg) to {
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
            },
            "SBS layout: ${s.sbsLayout.label}" to {
                choose("SBS layout", SbsLayout.entries.map { it.label }, s.sbsLayout.ordinal) { i ->
                    viewModel.update { it.copy(sbsLayout = SbsLayout.entries[i]) }
                }
            },
            "Lens calibration: ${if (s.useFactoryCalibration) "factory" else "sensor orientation only"}" to {
                choose(
                    "Lens calibration",
                    listOf("Factory (lens poses from the camera)", "Sensor orientation only"),
                    if (s.useFactoryCalibration) 0 else 1,
                ) { i -> viewModel.update { it.copy(useFactoryCalibration = i == 0) } }
            },
            "Manual alignment: pitch %+.2f°, roll %+.2f°".format(s.alignPitchDeg, s.alignRollDeg) to ::showAlignmentDialog,
            "Reset view adjustments" to {
                val d = StereoSettings()
                viewModel.update { it.copy(convergencePx = d.convergencePx, depth = d.depth, exposure = d.exposure) }
            },
        )
        AlertDialog.Builder(context)
            .setTitle("Settings")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
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
