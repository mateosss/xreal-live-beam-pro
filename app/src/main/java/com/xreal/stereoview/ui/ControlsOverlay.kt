package com.xreal.stereoview.ui

import android.content.res.ColorStateList
import android.util.Range
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.xreal.stereoview.R
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.databinding.OverlayControlsBinding
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The control panel on the Beam Pro touchscreen. When the stereo view goes to a separate
 * glasses display, the panel stays on the phone only; when the glasses mirror the phone, the
 * panel is drawn over the view and can be hidden with a tap, or collapsed to its toolbar.
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
        fun onRecord()
        fun isRecording(): Boolean

        /** Selectable camera pairs as (key, description), best first. */
        fun cameraPairOptions(): List<Pair<String, String>>
    }

    private val context = binding.root.context
    private var exposureRange = Range(0, 0)
    private var rig: StereoRig? = null
    private var collapsed = false
    private var recordingShown = false
    /** Set while [render] moves widgets, so their listeners don't write the values back. */
    private var rendering = false

    private val settingsSheet = SettingsSheet(context, viewModel, object : SettingsSheet.Host {
        override val rig: StereoRig? get() = this@ControlsOverlay.rig
        override fun isRecording() = actions.isRecording()
        override fun cameraPairOptions() = actions.cameraPairOptions()
        override fun setStatus(text: CharSequence) = this@ControlsOverlay.setStatus(text)
        override fun showProfiles() = profilesDialog.show()
    })
    private val profilesDialog = ProfilesDialog(context, viewModel, actions::isRecording, ::setStatus)

    init {
        binding.playButton.setOnClickListener { actions.onPlayPause() }
        binding.infoButton.setOnClickListener { actions.onShowInfo() }
        binding.alignButton.setOnClickListener { actions.onAutoAlign() }
        binding.recordButton.setOnClickListener { actions.onRecord() }
        binding.settingsButton.setOnClickListener { settingsSheet.show() }
        binding.profileButton.setOnClickListener { profilesDialog.show() }
        binding.collapseButton.setOnClickListener { setCollapsed(!collapsed) }
        binding.swapButton.setOnClickListener { viewModel.update { it.copy(swapEyes = !it.swapEyes) } }
        binding.hudButton.setOnClickListener { viewModel.update { it.copy(showHud = !it.showHud) } }
        binding.scaleButton.setOnClickListener {
            viewModel.update {
                val modes = ScaleMode.entries
                it.copy(scaleMode = modes[(it.scaleMode.ordinal + 1) % modes.size])
            }
        }

        binding.convergenceSlider.onUserChange { v -> viewModel.update { it.copy(convergencePx = v) } }
        binding.depthSlider.onUserChange { v ->
            val depth = StereoSettings.DEPTH_MIN + (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * v / 100f
            viewModel.update { it.copy(depth = depth) }
        }
        binding.brightnessSlider.onUserChange { v -> viewModel.update { it.copy(exposure = v.roundToInt()) } }
        binding.shutterSlider.onUserChange { v ->
            viewModel.update {
                val (min, max) = shutterRange(it)
                it.copy(exposureTimeNs = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).toLong())
            }
        }
        binding.isoSlider.onUserChange { v ->
            val (min, max) = isoRange()
            viewModel.update { it.copy(iso = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).roundToInt()) }
        }
        binding.exposureModeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (rendering || !checked) return@addOnButtonCheckedListener
            val manual = id == R.id.exposureManualButton
            if (manual && rig?.manualSensor == false) {
                setStatus("This camera does not support manual exposure")
                render(viewModel.settings.value)
            } else {
                viewModel.update { it.copy(manualExposure = manual) }
            }
        }
    }

    fun render(s: StereoSettings) {
        rendering = true
        val profiles = viewModel.profiles.value
        val active = profiles.activeProfile
        binding.profileButton.text = when {
            active == null -> "No profile"
            active.differsFrom(s) -> "${active.name} •"
            else -> active.name
        }

        binding.convergenceSlider.setSafe(s.convergencePx)
        binding.convergenceLabel.text = context.getString(R.string.convergence) + "  %+.0f px".format(s.convergencePx)
        binding.depthSlider.setSafe((s.depth - StereoSettings.DEPTH_MIN) / (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * 100)
        binding.depthLabel.text = context.getString(R.string.depth) + "  %.2f×".format(s.depth)

        val manual = s.manualExposure && rig?.manualSensor != false
        binding.exposureModeGroup.check(if (manual) R.id.exposureManualButton else R.id.exposureAutoButton)
        binding.brightnessColumn.visibility = if (manual) View.GONE else View.VISIBLE
        binding.shutterColumn.visibility = if (manual) View.VISIBLE else View.GONE
        binding.isoColumn.visibility = if (manual) View.VISIBLE else View.GONE
        binding.brightnessSlider.setSafe(s.exposure.toFloat())
        binding.brightnessLabel.text = context.getString(R.string.brightness) + "  %+d".format(s.exposure)
        val (tMin, tMax) = shutterRange(s)
        val shutter = s.exposureTimeNs.coerceIn(tMin, tMax)
        binding.shutterSlider.setSafe((toLog(shutter.toDouble(), tMin.toDouble(), tMax.toDouble()) * 1000).toFloat())
        binding.shutterLabel.text = "Shutter  1/%d s".format((1e9 / shutter).roundToInt())
        val (isoMin, isoMax) = isoRange()
        val iso = s.iso.coerceIn(isoMin, isoMax)
        binding.isoSlider.setSafe((toLog(iso.toDouble(), isoMin.toDouble(), isoMax.toDouble()) * 1000).toFloat())
        binding.isoLabel.text = "ISO  $iso"

        setActive(binding.swapButton, s.swapEyes)
        setActive(binding.hudButton, s.showHud)
        binding.scaleButton.text = s.scaleMode.label
        settingsSheet.refresh()
        profilesDialog.refresh()
        rendering = false
    }

    /** Adapts the exposure controls and frame-rate options to the streaming rig. */
    fun setRig(rig: StereoRig) {
        this.rig = rig
        exposureRange = rig.exposureRange
        binding.brightnessSlider.valueFrom = exposureRange.lower.toFloat()
        binding.brightnessSlider.valueTo = maxOf(exposureRange.upper, exposureRange.lower + 1).toFloat()
        binding.brightnessSlider.isEnabled = exposureRange.upper > exposureRange.lower
        binding.exposureManualButton.isEnabled = rig.manualSensor
        render(viewModel.settings.value)
    }

    fun setPlaying(playing: Boolean) {
        binding.playButton.setText(if (playing) R.string.pause else R.string.play)
        binding.playButton.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun setStatus(text: CharSequence) {
        binding.statusText.text = text
    }

    /** Shows the elapsed time on a filled red button while recording, else the outlined Rec button. */
    fun setRecording(elapsedSec: Long?) {
        if ((elapsedSec != null) != recordingShown) {
            recordingShown = elapsedSec != null
            settingsSheet.refresh() // lock or unlock its rows
        }
        val red = ContextCompat.getColor(context, R.color.record)
        val button = binding.recordButton
        if (elapsedSec == null) {
            button.text = context.getString(R.string.record)
            button.setIconResource(R.drawable.ic_record)
            button.backgroundTintList = ColorStateList.valueOf(0)
            button.setTextColor(red)
            button.iconTint = ColorStateList.valueOf(red)
        } else {
            button.text = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)
            button.setIconResource(R.drawable.ic_stop)
            button.backgroundTintList = ColorStateList.valueOf(red)
            button.setTextColor(0xFFFFFFFF.toInt())
            button.iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        binding.sliders.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.collapseButton.setIconResource(if (collapsed) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    /** Toggle look for plain buttons: tinted background when on. */
    private fun setActive(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) ContextCompat.getColor(context, R.color.accent_container) else 0,
        )
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

    private fun Slider.onUserChange(onChange: (Float) -> Unit) {
        addOnChangeListener { _, value, fromUser -> if (fromUser) onChange(value) }
    }

    /** Sets a value the slider accepts: inside its range and on a step (it throws otherwise). */
    private fun Slider.setSafe(v: Float) {
        val step = if (stepSize > 0) stepSize else 1f
        val snapped = valueFrom + ((v.coerceIn(valueFrom, valueTo) - valueFrom) / step).roundToInt() * step
        value = snapped.coerceIn(valueFrom, valueTo)
    }
}
