package com.xreal.stereoview.device

import androidx.appcompat.app.AppCompatActivity
import com.xreal.stereoview.preview.CapturePreview
import com.xreal.stereoview.record.ActiveRecording
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.ControlsOverlay
import com.xreal.stereoview.ui.StereoSettings
import org.json.JSONObject

/**
 * What a [DeviceSession] needs from the screen that runs it. Device threads read [renderer],
 * [recording] and [capture] (volatile there); the rest is for the main thread unless noted.
 */
interface SessionHost {
    val activity: AppCompatActivity
    val controls: ControlsOverlay
    val settings: StereoSettings
    val playing: Boolean
    val renderer: StereoRenderer?
    val recording: ActiveRecording?

    /** The portrait capture view, while shown: device threads feed its tiles and IMU plots. */
    val capture: CapturePreview?

    /** Shows [message] in the status line; any thread. */
    fun showStatus(message: String)

    /** Shows [message] as a warning; any thread. */
    fun showError(message: String)

    /** The device's state changed: refresh the status line; any thread. */
    fun refreshStatus()

    fun stopRecording(reason: String)

    /** Starts a recording made by [create] (see MainActivity.beginRecording); null if it couldn't start. */
    fun <R : ActiveRecording> beginRecording(what: String, usb: Boolean, create: () -> R): R?

    /** Context for metadata.json that only the screen knows. */
    fun recordingInfo(): JSONObject
}

/** What the portrait capture view shows for a device. */
class CaptureLayout(val tiles: List<CapturePreview.Tile>, val imuTitle: String, val imuOn: Boolean)

/**
 * One camera source as the live screen runs it: starting and stopping its stream, applying
 * setting changes, its HUD and status lines, its recordings and what the capture view shows.
 * The screen runs one session at a time (the profile's [CameraSource]); adding a device means
 * adding a session, not branches in the screen.
 */
abstract class DeviceSession(protected val host: SessionHost) {
    abstract val source: CameraSource
    abstract val isRunning: Boolean

    /** Starts streaming with the current settings; main thread. */
    abstract fun start()

    /** Stops streaming (and this device's recording, if one runs); main thread. */
    abstract fun stop()

    /** Another source takes over the screen; by default [stop]. */
    open fun onOtherSourceStarting() = stop()

    /** Stops for a restart with new settings; by default [stop] then [start]. */
    open fun restart() {
        stop()
        start()
    }

    /** Stops when the screen's output goes away (the app is hidden); waits if the device needs it. */
    open fun shutdown() = stop()

    /** Releases the device for good (the screen is destroyed). */
    open fun release() {}

    /**
     * Whether the change from [previous] to [s] needs the stream restarted (while it plays):
     * the message to stop a running recording with, or null if [applyLive] can apply it.
     */
    abstract fun restartReason(s: StereoSettings, previous: StereoSettings): String?

    /** Applies a change that needs no restart (e.g. exposure) to the running device. */
    abstract fun applyLive(s: StereoSettings, previous: StereoSettings)

    /** The device's HUD lines (the screen adds the recording backlog and the view's). */
    abstract fun hud(stats: StereoRenderer.RenderStats): String

    /** What the status line says about this device while playing. */
    abstract val status: String

    /** An extra warning for the status line, or "". */
    open val statusWarning: String get() = ""

    abstract fun startRecording()

    /** Why [recording] (this device's) can't go on, e.g. the stream stopped; null if it can. */
    open fun streamLost(recording: ActiveRecording): String? = null

    /** Why auto-align can't run now, or null. */
    abstract fun alignBlocker(s: StereoSettings): String?

    abstract fun captureLayout(s: StereoSettings): CaptureLayout

    /** The capture view was laid out for this device: feed it what the session's threads don't. */
    open fun onCaptureShown(view: CapturePreview) {}

    open fun onCaptureHidden() {}

    /** The renderer draws into the capture view's camera area (the phone's cameras reach only the renderer). */
    open val drawsInCaptureArea: Boolean get() = false

    /** A running recording stopped (any device's): drop references to it. */
    open fun onRecordingStopped() {}
}
