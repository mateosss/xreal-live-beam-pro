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
import com.xreal.stereoview.glasses.GlassesDevice
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import android.widget.LinearLayout.LayoutParams as LP

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
        fun onUsbProbe()
        fun isRecording(): Boolean

        /** The folder being recorded into, if any. */
        fun recordingDir(): java.io.File?

        /** Opens the system file picker; [onPicked] gets the chosen file's uri and display name. */
        fun pickFile(onPicked: (android.net.Uri, String?) -> Unit)

        /** Selectable camera pairs as (key, description), best first. */
        fun cameraPairOptions(): List<Pair<String, String>>
    }

    private val context = binding.root.context
    private var exposureRange = Range(0, 0)
    private var rig: StereoRig? = null
    private var glassesControls: GlassesDevice.CameraControls? = null
    private var collapsed = false
    private var recordingShown = false

    /** OAK: the camera the exposure controls set, −1 for all (cam0's values shown). */
    private var oakTarget = -1

    /** Set while [render] moves widgets, so their listeners don't write the values back. */
    private var rendering = false

    private val settingsSheet = SettingsSheet(context, viewModel, object : SettingsSheet.Host {
        override val rig: StereoRig? get() = this@ControlsOverlay.rig
        override val glassesControls: GlassesDevice.CameraControls? get() = this@ControlsOverlay.glassesControls
        override fun isRecording() = actions.isRecording()
        override fun cameraPairOptions() = actions.cameraPairOptions()
        override fun setStatus(text: CharSequence) = this@ControlsOverlay.setStatus(text)
        override fun showProfiles() = profilesDialog.show()
        override fun probeUsb() = actions.onUsbProbe()
        override fun showRecordings() = RecordingsActivity.open(context, actions.recordingDir())
        override fun pickFile(onPicked: (android.net.Uri, String?) -> Unit) = actions.pickFile(onPicked)
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
                if (portrait) {
                    // The phone's portrait preview has its own mode (all but 1:1).
                    val modes = PORTRAIT_SCALE_MODES
                    it.copy(portraitScaleMode = modes[(modes.indexOf(portraitMode(it.portraitScaleMode)) + 1) % modes.size])
                } else {
                    val modes = ScaleMode.entries
                    it.copy(scaleMode = modes[(it.scaleMode.ordinal + 1) % modes.size])
                }
            }
        }

        binding.convergenceSlider.onUserChange { v -> viewModel.update { it.copy(convergencePx = v) } }
        binding.depthSlider.onUserChange { v ->
            val depth = StereoSettings.DEPTH_MIN + (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * v / 100f
            viewModel.update { it.copy(depth = depth) }
        }
        binding.oakCameraButton.setOnClickListener {
            val s = viewModel.settings.value
            val cameras = if (s.oakRgbHeight > 0) 3 else 2
            oakTarget = if (oakTarget + 1 >= cameras) -1 else oakTarget + 1
            setStatus("Exposure controls set " + if (oakTarget < 0) "all OAK cameras alike" else com.xreal.stereoview.oak.OakDevice.Exposure.CAMERAS[oakTarget])
            render(s)
        }
        binding.brightnessSlider.onUserChange { v ->
            if (viewModel.settings.value.cameraSource == CameraSource.OAK) updateOak { it.copy(compensation = v.roundToInt()) }
            else viewModel.update { it.copy(exposure = v.roundToInt()) }
        }
        binding.shutterSlider.onUserChange { v ->
            if (viewModel.settings.value.cameraSource == CameraSource.OAK) {
                val (min, max) = shutterRange(viewModel.settings.value, oakTarget == 2)
                updateOak { it.copy(exposureUs = (fromLog(v / 1000.0, min.toDouble(), max.toDouble()) / 1000).roundToInt()) }
                return@onUserChange
            }
            viewModel.update {
                if (it.cameraSource == CameraSource.GLASSES) {
                    val (min, max) = glassesExposureRange() ?: return@update it
                    it.copy(glassesExposure100us = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).roundToInt())
                } else {
                    val (min, max) = shutterRange(it)
                    it.copy(exposureTimeNs = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).toLong())
                }
            }
        }
        binding.isoSlider.onUserChange { v ->
            if (viewModel.settings.value.cameraSource == CameraSource.OAK) {
                val (min, max) = isoRange()
                updateOak { it.copy(iso = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).roundToInt()) }
                return@onUserChange
            }
            viewModel.update {
                if (it.cameraSource == CameraSource.GLASSES) {
                    val r = glassesControls?.gain ?: return@update it
                    it.copy(glassesGain = (r.min + (r.max - r.min) * v / 1000.0).roundToInt())
                } else {
                    val (min, max) = isoRange()
                    it.copy(iso = fromLog(v / 1000.0, min.toDouble(), max.toDouble()).roundToInt())
                }
            }
        }
        binding.exposureModeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (rendering || !checked) return@addOnButtonCheckedListener
            val manual = id == R.id.exposureManualButton
            if (viewModel.settings.value.cameraSource == CameraSource.OAK) {
                updateOak { it.copy(auto = !manual) }
            } else if (viewModel.settings.value.cameraSource == CameraSource.GLASSES) {
                viewModel.update { it.copy(glassesManualExposure = manual) }
            } else if (manual && rig?.manualSensor == false) {
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

        renderStereoControls(s)
        binding.convergenceSlider.setSafe(s.convergencePx)
        binding.convergenceLabel.text = context.getString(R.string.convergence) + "  %+.0f px".format(s.convergencePx)
        binding.depthSlider.setSafe((s.depth - StereoSettings.DEPTH_MIN) / (StereoSettings.DEPTH_MAX - StereoSettings.DEPTH_MIN) * 100)
        binding.depthLabel.text = context.getString(R.string.depth) + "  %.2f×".format(s.depth)

        binding.oakCameraButton.visibility = if (s.cameraSource == CameraSource.OAK) View.VISIBLE else View.GONE
        when (s.cameraSource) {
            CameraSource.GLASSES -> renderGlassesExposure(s)
            CameraSource.OAK -> renderOakExposure(s)
            CameraSource.BEAM_PRO -> renderBeamProExposure(s)
        }

        setActive(binding.swapButton, s.swapEyes)
        setActive(binding.hudButton, s.showHud)
        binding.scaleButton.text = if (portrait) portraitLabel(s) else s.scaleMode.label
        settingsSheet.refresh()
        profilesDialog.refresh()
        rendering = false
    }

    private fun renderBeamProExposure(s: StereoSettings) {
        val manual = s.manualExposure && rig?.manualSensor != false
        binding.exposureModeGroup.check(if (manual) R.id.exposureManualButton else R.id.exposureAutoButton)
        binding.exposureManualButton.isEnabled = rig?.manualSensor != false
        binding.exposureModeGroup.visibility = View.VISIBLE
        binding.brightnessColumn.visibility = if (!manual) View.VISIBLE else View.GONE
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
    }

    /** Exposure of the OAK camera(s) the controls set ([oakTarget]): auto with compensation, or shutter and ISO. */
    private fun renderOakExposure(s: StereoSettings) {
        if (oakTarget == 2 && s.oakRgbHeight <= 0) oakTarget = -1
        val e = s.oakExposures[oakTarget.coerceAtLeast(0)]
        val alike = s.oakExposures.take(if (s.oakRgbHeight > 0) 3 else 2).distinct().size == 1
        binding.oakCameraButton.text = if (oakTarget < 0) (if (alike) "All" else "All ≠") else "cam$oakTarget"
        binding.exposureModeGroup.visibility = View.VISIBLE
        binding.exposureManualButton.isEnabled = true
        binding.exposureModeGroup.check(if (e.auto) R.id.exposureAutoButton else R.id.exposureManualButton)
        binding.brightnessColumn.visibility = if (e.auto) View.VISIBLE else View.GONE
        binding.shutterColumn.visibility = if (e.auto) View.GONE else View.VISIBLE
        binding.isoColumn.visibility = if (e.auto) View.GONE else View.VISIBLE
        binding.brightnessSlider.setSafe(e.compensation.toFloat())
        binding.brightnessLabel.text = context.getString(R.string.brightness) + "  %+d".format(e.compensation)
        val (tMin, tMax) = shutterRange(s, oakTarget == 2)
        val shutter = (e.exposureUs * 1000L).coerceIn(tMin, tMax)
        binding.shutterSlider.setSafe((toLog(shutter.toDouble(), tMin.toDouble(), tMax.toDouble()) * 1000).toFloat())
        binding.shutterLabel.text = if (e.exposureUs >= 1000) "Shutter  %.1f ms".format(e.exposureUs / 1000.0) else "Shutter  ${e.exposureUs} µs"
        val (isoMin, isoMax) = isoRange()
        binding.isoSlider.setSafe((toLog(e.iso.coerceIn(isoMin, isoMax).toDouble(), isoMin.toDouble(), isoMax.toDouble()) * 1000).toFloat())
        binding.isoLabel.text = "ISO  ${e.iso}"
    }

    /**
     * Changes the exposure of the OAK camera the controls set: [oakTarget], or all of them, which
     * then all take the shown (cam0's) values with the change.
     */
    private fun updateOak(change: (com.xreal.stereoview.oak.OakDevice.Exposure) -> com.xreal.stereoview.oak.OakDevice.Exposure) {
        viewModel.update { s ->
            val e = change(s.oakExposures[oakTarget.coerceAtLeast(0)]).clamped()
            s.copy(oakExposures = s.oakExposures.mapIndexed { i, old -> if (oakTarget < 0 || i == oakTarget) e else old })
        }
    }

    /**
     * The glasses cameras' exposure controls, as far as they advertise them over UVC: the
     * Auto/Manual switch, exposure time and gain. Hidden when they offer none.
     */
    private fun renderGlassesExposure(s: StereoSettings) {
        val c = glassesControls
        val exposureRange = glassesExposureRange()
        val manual = s.glassesManualExposure && exposureRange != null
        binding.exposureModeGroup.visibility = if (exposureRange != null) View.VISIBLE else View.GONE
        binding.exposureManualButton.isEnabled = exposureRange != null
        binding.exposureModeGroup.check(if (manual) R.id.exposureManualButton else R.id.exposureAutoButton)
        binding.brightnessColumn.visibility = View.GONE
        binding.shutterColumn.visibility = if (manual) View.VISIBLE else View.GONE
        if (exposureRange != null) {
            val (min, max) = exposureRange
            val e = s.glassesExposure100us.coerceIn(min, max)
            binding.shutterSlider.setSafe((toLog(e.toDouble(), min.toDouble(), max.toDouble()) * 1000).toFloat())
            binding.shutterLabel.text = "Exposure  %.1f ms".format(e / 10.0)
        }
        val gain = c?.gain
        binding.isoColumn.visibility = if (gain != null && (manual || exposureRange == null)) View.VISIBLE else View.GONE
        if (gain != null) {
            val g = if (s.glassesGain < 0) gain.default else s.glassesGain.coerceIn(gain.min, gain.max)
            val t = if (gain.max > gain.min) (g - gain.min) * 1000f / (gain.max - gain.min) else 0f
            binding.isoSlider.setSafe(t)
            binding.isoLabel.text = "Gain  $g" + if (s.glassesGain < 0) " (default)" else ""
        }
    }

    /** Exposure range of the glasses cameras in 100 µs units, capped at one frame of a camera (30 fps), or null. */
    private fun glassesExposureRange(): Pair<Int, Int>? {
        val r = glassesControls?.exposure ?: return null
        val min = maxOf(r.min, 1)
        val max = minOf(r.max, 333).coerceAtLeast(min + 1)
        return min to max
    }

    /** The glasses cameras' controls once they stream (null otherwise). */
    fun setGlassesControls(controls: GlassesDevice.CameraControls?) {
        glassesControls = controls
        render(viewModel.settings.value)
    }

    /**
     * Portrait (narrow screen): the actions get their own scrollable row under the profile and
     * record buttons; landscape: they sit between them.
     */
    private var portrait = false

    /**
     * Convergence, depth and align only shape the stereo (passthrough) view: the capture-style
     * portrait preview shows every camera as streamed, so they are hidden there.
     */
    private fun renderStereoControls(s: StereoSettings) {
        val stereo = !(portrait && s.previewStyle == PreviewStyle.CAPTURE)
        val visibility = if (stereo) View.VISIBLE else View.GONE
        binding.stereoSlidersRow.visibility = visibility
        binding.alignButton.visibility = visibility
    }

    private fun portraitLabel(s: StereoSettings) = portraitMode(s.portraitScaleMode).label

    fun setPortrait(portrait: Boolean) {
        if (this.portrait != portrait) {
            this.portrait = portrait
            viewModel.settings.value.let {
                binding.scaleButton.text = if (portrait) portraitLabel(it) else it.scaleMode.label
                renderStereoControls(it)
            }
        }
        layoutExposure(portrait)
        val actions = binding.actionsScroll
        val target = if (portrait) binding.portraitActions else binding.toolbarRow
        if (actions.parent === target) return
        (actions.parent as android.view.ViewGroup).removeView(actions)
        if (portrait) {
            target.addView(actions, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            binding.toolbarRow.addView(android.view.View(context), 1, android.widget.LinearLayout.LayoutParams(0, 1, 1f))
        } else {
            binding.toolbarRow.removeViewAt(1) // the spacer
            binding.toolbarRow.addView(actions, 1, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        binding.portraitActions.visibility = if (portrait) View.VISIBLE else View.GONE
        binding.actionsRow.gravity = (if (portrait) android.view.Gravity.START else android.view.Gravity.END) or android.view.Gravity.CENTER_VERTICAL
    }

    /**
     * Landscape: the exposure sliders beside the mode buttons, sharing the row. Portrait: below
     * them, each across the full width, and the sliders area scrolls past 40% of the screen.
     */
    private fun layoutExposure(portrait: Boolean) {
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.exposureRow.orientation = if (portrait) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        binding.exposureRow.gravity = if (portrait) android.view.Gravity.START else android.view.Gravity.CENTER_VERTICAL
        binding.exposureSliders.orientation = if (portrait) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        binding.exposureSliders.layoutParams = if (portrait) LP(match, wrap) else LP(0, wrap, 1f)
        for ((column, weight) in listOf(binding.brightnessColumn to 2f, binding.shutterColumn to 1f, binding.isoColumn to 1f)) {
            column.layoutParams = if (portrait) LP(match, wrap) else LP(0, wrap, weight)
        }
        binding.slidersScroll.maxHeightPx = if (portrait) (context.resources.displayMetrics.heightPixels * 0.4).toInt() else 0
    }

    /** OAK cameras: auto exposure with compensation −9..9, or manual shutter and ISO 100–1600. */
    fun setOakMode(on: Boolean) {
        if (!on) return
        rig = null
        exposureRange = Range(-9, 9)
        binding.brightnessSlider.valueFrom = -9f
        binding.brightnessSlider.valueTo = 9f
        binding.brightnessSlider.isEnabled = true
        render(viewModel.settings.value)
    }

    /** Adapts the exposure controls and frame-rate options to the streaming rig. */
    fun setRig(rig: StereoRig) {
        this.rig = rig
        exposureRange = rig.exposureRange
        binding.brightnessSlider.valueFrom = exposureRange.lower.toFloat()
        binding.brightnessSlider.valueTo = maxOf(exposureRange.upper, exposureRange.lower + 1).toFloat()
        binding.brightnessSlider.isEnabled = exposureRange.upper > exposureRange.lower
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
    private fun shutterRange(s: StereoSettings, oakColor: Boolean = false): Pair<Long, Long> {
        val range = rig?.exposureTimeRange
        val min = maxOf(range?.lower ?: 50_000L, 50_000L)
        // OAK: the sensors' own rate (higher than the mono rate when the color camera runs faster).
        val fps = if (s.cameraSource == CameraSource.OAK) {
            val plan = com.xreal.stereoview.oak.OakDevice.FpsPlan.of(s.oakFps, if (s.oakRgbHeight > 0 && s.oakRgbFps > 0) s.oakRgbFps else s.oakFps)
            if (oakColor) plan.rgbSensorFps else plan.monoSensorFps
        } else {
            s.targetFps
        }
        val max = minOf(range?.upper ?: 33_333_333L, 1_000_000_000L / fps)
        return min to maxOf(max, min + 1)
    }

    private fun isoRange(): Pair<Int, Int> {
        val range = rig?.sensitivityRange
        val min = range?.lower ?: 100
        val max = if (viewModel.settings.value.cameraSource == CameraSource.OAK) 1600 else range?.upper ?: 3200
        return min to maxOf(max, min + 1)
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
