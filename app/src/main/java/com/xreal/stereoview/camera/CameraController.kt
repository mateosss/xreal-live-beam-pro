package com.xreal.stereoview.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Opens the cameras of a [StereoRig] and streams them into two SurfaceTextures
 * (index 0 = left camera, 1 = right camera). All camera work runs on one background thread.
 *
 * If a rig cannot be opened, or no stream size can be configured, its fallback rig is tried.
 */
class CameraController(context: Context, private val listener: Listener) {
    interface Listener {
        fun onCameraStatus(message: String)
        fun onCameraStreaming(rig: StereoRig, size: Size)
        fun onCameraError(message: String)

        /** What the sensor actually used for a recent frame; called about twice a second. */
        fun onCaptureStats(exposureTimeNs: Long, iso: Int, frameDurationNs: Long) = Unit
    }

    /**
     * Auto exposure with [compensation] steps, or a fixed [exposureTimeNs] and [iso]; and
     * autofocus, or focus locked at infinity.
     */
    data class Exposure(
        val compensation: Int = 0,
        val manual: Boolean = false,
        val exposureTimeNs: Long = 4_000_000,
        val iso: Int = 400,
        val focusInfinity: Boolean = false,
    )

    /** Receives every capture result of every open device, on the camera thread. */
    fun interface ResultListener {
        fun onCaptureResult(deviceId: String, result: TotalCaptureResult)
    }

    @Volatile
    var resultListener: ResultListener? = null

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("StereoCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // Everything below is only touched on [thread].
    private var rig: StereoRig? = null
    private var textures: Pair<SurfaceTexture, SurfaceTexture>? = null
    private var surfaces: List<Surface> = emptyList()
    private var sizeIndex = 0
    private var exposure = Exposure()
    private var lastStatsMs = 0L
    /** Bumped whenever cameras are closed or reconfigured; stale callbacks compare against it. */
    private var generation = 0
    private val devices = LinkedHashMap<String, CameraDevice>()
    private val sessions = HashMap<String, CameraCaptureSession>()

    fun start(rig: StereoRig, left: SurfaceTexture, right: SurfaceTexture, exposure: Exposure) {
        handler.post {
            closeCameras()
            releaseSurfaces()
            this.rig = rig
            this.exposure = exposure
            textures = left to right
            surfaces = listOf(Surface(left), Surface(right))
            sizeIndex = 0
            open()
        }
    }

    /** Closes the cameras but keeps the rig and surfaces, so [start] or [resume] can reopen. */
    fun pause() {
        handler.post { closeCameras() }
    }

    fun resume() {
        handler.post { if (devices.isEmpty() && rig != null && surfaces.isNotEmpty()) open() }
    }

    fun setExposure(value: Exposure) {
        handler.post {
            exposure = value
            sessions.keys.forEach(::applyRepeating)
        }
    }

    /** Closes everything and waits, so the caller can safely release the SurfaceTextures. */
    fun stopBlocking() {
        val done = CountDownLatch(1)
        handler.post {
            closeCameras()
            releaseSurfaces()
            rig = null
            textures = null
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        stopBlocking()
        thread.quitSafely()
    }

    private fun open() {
        val rig = rig ?: return
        val gen = ++generation
        listener.onCameraStatus("Opening ${rig.description}…")
        openNext(rig, 0, gen)
    }

    /** Opens the rig's devices one after another; some HALs reject simultaneous opens. */
    @SuppressLint("MissingPermission")
    private fun openNext(rig: StereoRig, index: Int, gen: Int) {
        val ids = rig.deviceIds
        if (index == ids.size) {
            configure(rig, gen)
            return
        }
        val id = ids[index]
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (gen != generation) {
                        device.close()
                        return
                    }
                    devices[id] = device
                    openNext(rig, index + 1, gen)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (devices[id] === device) {
                        closeCameras()
                        listener.onCameraError("Camera $id was disconnected (in use by another app?). Press Play to retry.")
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (gen == generation || devices[id] === device) {
                        fail(rig, "Camera $id error: ${errorName(error)}")
                    }
                }
            })
        } catch (e: Exception) {
            when (e) {
                is CameraAccessException, is SecurityException, is IllegalArgumentException ->
                    fail(rig, "Cannot open camera $id: ${e.message}")
                else -> throw e
            }
        }
    }

    private fun configure(rig: StereoRig, gen: Int) {
        val (left, right) = textures ?: return
        val size = rig.sizes[sizeIndex]
        left.setDefaultBufferSize(size.width, size.height)
        right.setDefaultBufferSize(size.width, size.height)

        var remaining = devices.size
        var failed = false
        for ((id, device) in devices) {
            val outputs = rig.targetsFor(id).map { (eye, target) ->
                OutputConfiguration(surfaces[eye]).apply { target.physicalId?.let(::setPhysicalCameraId) }
            }
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (gen != generation) {
                        session.close()
                        return
                    }
                    sessions[id] = session
                    applyRepeating(id)
                    if (--remaining == 0) listener.onCameraStreaming(rig, size)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (gen != generation || failed) return
                    failed = true
                    configureFailed(rig, size)
                }
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            try {
                if (!isSupported(device, config)) {
                    configureFailed(rig, size)
                    return
                }
                device.createCaptureSession(config)
            } catch (e: Exception) {
                when (e) {
                    is CameraAccessException, is IllegalArgumentException, is IllegalStateException -> {
                        Log.w(TAG, "createCaptureSession failed for $id at $size", e)
                        configureFailed(rig, size)
                        return
                    }
                    else -> throw e
                }
            }
        }
    }

    /** Asks the HAL up front when it can tell us; many HALs cannot and throw instead. */
    private fun isSupported(device: CameraDevice, config: SessionConfiguration): Boolean = try {
        device.isSessionConfigurationSupported(config)
    } catch (e: UnsupportedOperationException) {
        true
    } catch (e: IllegalArgumentException) {
        true
    }

    /** Retries with the next smaller size, then falls back to the next rig. */
    private fun configureFailed(rig: StereoRig, size: Size) {
        sessions.values.forEach { it.close() }
        sessions.clear()
        if (sizeIndex + 1 < rig.sizes.size) {
            sizeIndex++
            listener.onCameraStatus("Session at $size not supported, trying ${rig.sizes[sizeIndex]}…")
            configure(rig, ++generation)
        } else {
            fail(rig, "Could not configure ${rig.mode.label} streams")
        }
    }

    private fun fail(rig: StereoRig, message: String) {
        Log.w(TAG, message)
        closeCameras()
        val fallback = rig.fallback
        if (fallback != null && surfaces.isNotEmpty()) {
            listener.onCameraStatus("$message — falling back to ${fallback.mode.label}")
            this.rig = fallback
            sizeIndex = 0
            open()
        } else {
            listener.onCameraError(message)
        }
    }

    private fun applyRepeating(deviceId: String) {
        val rig = rig ?: return
        val device = devices[deviceId] ?: return
        val session = sessions[deviceId] ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                rig.targetsFor(deviceId).forEach { (eye, _) -> addTarget(surfaces[eye]) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (exposure.manual && rig.manualSensor) {
                    // AE off: the frame duration sets the frame rate, so it can exceed the AE ranges.
                    val frameNs = 1_000_000_000L / rig.targetFps
                    val time = rig.exposureTimeRange?.let { exposure.exposureTimeNs.coerceIn(it.lower, it.upper) } ?: exposure.exposureTimeNs
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, time.coerceAtMost(frameNs))
                    set(CaptureRequest.SENSOR_SENSITIVITY, rig.sensitivityRange?.let { exposure.iso.coerceIn(it.lower, it.upper) } ?: exposure.iso)
                } else {
                    rig.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure.compensation.coerceIn(rig.exposureRange.lower, rig.exposureRange.upper))
                }
                // Per-camera stabilization and HAL undistortion would move each eye's image
                // independently; the renderer stabilizes and undistorts both eyes consistently.
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                if (rig.canDisableOis) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
                if (rig.undistortInShader) {
                    set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                }
                val minFocus = manager.getCameraCharacteristics(deviceId).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (exposure.focusInfinity && minFocus > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }
            }
            session.setRepeatingRequest(request.build(), resultCallback(deviceId, rig), handler)
        } catch (e: Exception) {
            when (e) {
                is CameraAccessException, is IllegalStateException, is IllegalArgumentException ->
                    Log.w(TAG, "setRepeatingRequest failed for $deviceId", e)
                else -> throw e
            }
        }
    }

    /** Forwards results to [resultListener]; only the first device reports stats, as both run the same settings. */
    private fun resultCallback(deviceId: String, rig: StereoRig) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            resultListener?.onCaptureResult(deviceId, result)
            if (deviceId != rig.deviceIds.first()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatsMs < 500) return
            lastStatsMs = now
            listener.onCaptureStats(
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
            )
        }
    }

    private fun closeCameras() {
        generation++
        sessions.values.forEach { it.close() }
        sessions.clear()
        devices.values.forEach { it.close() }
        devices.clear()
    }

    private fun releaseSurfaces() {
        surfaces.forEach { it.release() }
        surfaces = emptyList()
    }

    private fun errorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled by policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
        else -> "error $error"
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
