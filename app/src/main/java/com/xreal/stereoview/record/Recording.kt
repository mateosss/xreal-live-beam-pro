package com.xreal.stereoview.record

import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Size
import com.xreal.stereoview.camera.CameraController
import com.xreal.stereoview.camera.CameraTarget
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.render.StereoGeometry
import com.xreal.stereoview.render.StereoRenderer
import com.xreal.stereoview.ui.RecordFormat
import com.xreal.stereoview.ui.RecordView
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.Mat3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One dataset recording: per camera a video (or JPEG sequence), a frames.csv with the sensor
 * timestamp of every stored frame and a capture_results.csv with per-frame camera metadata;
 * IMU streams under imu/; settings changes in settings.jsonl; and metadata.json describing
 * the device, cameras, calibration and recording settings.
 *
 * Frames are stored by the renderer on its GL thread ([onFrameStored]); capture results arrive
 * on the camera thread ([onCaptureResult]). [finish] must run after the renderer has released
 * the encoders' input surfaces.
 */
class Recording private constructor(
    override val dir: File,
    val view: RecordView,
    val format: RecordFormat,
    val outputs: List<Output>,
    private val imu: ImuRecorder,
    private val settingsLog: LineWriter,
    private val metadata: JSONObject,
) : CameraController.ResultListener, ActiveRecording {

    /** One recorded camera. [camera] is the renderer's camera index (0 = rig's left, 1 = right). */
    class Output(
        val index: Int,
        val camera: Int,
        val target: CameraTarget,
        val size: Size,
        val video: VideoEncoder?,
        val jpeg: JpegWriter?,
        val frames: LineWriter,
        val results: LineWriter,
    ) {
        val name: String get() = "cam$index"
        var stored = 0L
        var firstNs = 0L
        var lastNs = 0L
        var maxGapNs = 0L
        val line = StringBuilder(200)
    }

    /** What a rectified eye shows: half-FOV tangents, horizontal shift (eye widths) and zoom. */
    data class EyeView(val tanH: Double, val tanV: Double, val shift: Double, val zoom: Double)

    override val startBoottimeNs: Long = SystemClock.elapsedRealtimeNanos()
    val mono: Boolean get() = outputs.size == 1

    /** Set when something fails mid-recording; the UI stops the recording and shows it. */
    @Volatile
    override var error: String? = null

    fun eyeView(geometry: StereoGeometry, s: StereoSettings, output: Output): EyeView =
        eyeView(geometry, s, output.index, output.size, mono)

    /** GL thread: a frame of [output] captured at [timestampNs] was handed to its encoder. */
    fun onFrameStored(output: Output, timestampNs: Long, intrinsics: DoubleArray?, stabilization: DoubleArray?) {
        val o = output
        if (o.stored == 0L) o.firstNs = timestampNs else o.maxGapNs = maxOf(o.maxGapNs, timestampNs - o.lastNs)
        o.lastNs = timestampNs
        val line = o.line
        line.setLength(0)
        line.append(o.stored).append(',').append(timestampNs).append(',').append(SystemClock.elapsedRealtimeNanos())
        if (view == RecordView.RECTIFIED) {
            val k = intrinsics ?: DoubleArray(4)
            for (v in k) line.append(',').append(v)
            val q = Mat3.toQuaternion(stabilization ?: Mat3.identity())
            for (v in q) line.append(',').append(v)
        }
        o.frames.line(line)
        o.stored++
    }

    /** Camera thread: logs the result for every output streamed by [deviceId]. */
    override fun onCaptureResult(deviceId: String, result: TotalCaptureResult) {
        val arrival = SystemClock.elapsedRealtimeNanos()
        for (o in outputs) {
            if (o.target.deviceId != deviceId) continue
            val r: CaptureResult = o.target.physicalId?.let { result.physicalCameraTotalResults[it] } ?: result
            val line = StringBuilder(200)
            line.append(result.frameNumber)
            fun col(v: Any?) {
                line.append(',')
                if (v != null) line.append(v)
            }
            col(r.get(CaptureResult.SENSOR_TIMESTAMP))
            col(readoutKey(r)?.let { r.get(it) })
            col(r.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            col(r.get(CaptureResult.SENSOR_FRAME_DURATION))
            col(r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
            col(r.get(CaptureResult.SENSOR_SENSITIVITY))
            col(r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
            col(r.get(CaptureResult.CONTROL_AE_STATE))
            col(r.get(CaptureResult.CONTROL_AF_STATE))
            col(r.get(CaptureResult.LENS_FOCUS_DISTANCE))
            col(r.get(CaptureResult.LENS_STATE))
            col(r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
            col(r.get(CaptureResult.DISTORTION_CORRECTION_MODE))
            val k = r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            for (i in 0 until 5) col(k?.getOrNull(i))
            col(arrival)
            o.results.line(line)
        }
    }

    // SENSOR_READOUT_TIMESTAMP is public from API 35; older HALs may still report it.
    @Volatile
    private var readoutKeyLookedUp = false
    private var readoutKey: CaptureResult.Key<*>? = null

    private fun readoutKey(r: CaptureResult): CaptureResult.Key<*>? {
        if (!readoutKeyLookedUp) {
            readoutKey = r.keys.firstOrNull { it.name == "android.sensor.readoutTimestamp" }
            readoutKeyLookedUp = true
        }
        return readoutKey
    }

    override fun onSettingsChanged(s: StereoSettings) {
        settingsLog.line(JSONObject().apply {
            put("boottime_ns", SystemClock.elapsedRealtimeNanos())
            put("settings", Metadata.settings(s))
        }.toString())
    }

    /** Closes encoders and files and completes metadata.json. Blocking; call off the main thread. */
    override fun finish(): String {
        val end = Metadata.clocks()
        val imuSummary = imu.stop()
        for (o in outputs) {
            o.video?.finish()
            o.jpeg?.finish()
            o.frames.close()
            o.results.close()
        }
        settingsLog.close()

        metadata.put("status", if (error == null) "complete" else "stopped with error: $error")
        metadata.put("clocks_at_end", end)
        metadata.put("duration_s", (end.getLong("boottime_ns") - startBoottimeNs) / 1e9)
        metadata.put("imu", imuSummary)
        val cams = metadata.getJSONArray("cameras")
        for (o in outputs) {
            cams.getJSONObject(o.index).put("stats", JSONObject().apply {
                put("frames", o.stored)
                put("first_timestamp_ns", o.firstNs)
                put("last_timestamp_ns", o.lastNs)
                if (o.stored > 1) put("mean_fps", (o.stored - 1) * 1e9 / (o.lastNs - o.firstNs))
                put("max_gap_ms", o.maxGapNs / 1e6)
                o.video?.let {
                    put("video_samples", it.samples)
                    put("encoder", it.codecName)
                    it.error?.let { e -> put("encoder_error", e) }
                }
                o.jpeg?.let {
                    put("jpeg_written", it.written.get())
                    put("jpeg_dropped", it.dropped.get())
                    put("jpeg_failed", it.failed.get())
                }
                put("capture_results", o.results.lines - 1)
            })
        }
        File(dir, "metadata.json").writeText(metadata.toString(2))

        return buildString {
            append("Saved ${dir.name}: ")
            append(outputs.joinToString { "${it.name} ${it.stored} frames" })
            outputs.firstNotNullOfOrNull { it.jpeg?.dropped?.get()?.takeIf { d -> d > 0 } }?.let { append(", $it JPEG frames dropped") }
            outputs.firstNotNullOfOrNull { it.video?.error }?.let { append(", encoder error: $it") }
            imuSummary.getJSONArray("streams").let { streams ->
                for (i in 0 until streams.length()) {
                    val t = streams.getJSONObject(i)
                    if (t.getString("stream") == "GYROSCOPE" || t.getString("stream") == "ACCELEROMETER") {
                        append(", ${t.getString("stream").lowercase()} %.0f Hz".format(t.optDouble("mean_rate_hz", 0.0)))
                    }
                }
            }
        }
    }

    companion object {
        /** Where recordings go: app-specific external storage, reachable over USB (MTP) and adb. */
        fun root(context: Context): File = File(context.getExternalFilesDir(null), "recordings")

        fun eyeView(geometry: StereoGeometry, s: StereoSettings, index: Int, size: Size, mono: Boolean): EyeView {
            // 1:1 only makes sense in the glasses; a recording keeps the widest view without borders.
            val mode = if (s.scaleMode == ScaleMode.REAL) ScaleMode.FILL else s.scaleMode
            val (tanH, tanV) = geometry.displayTangents(mode, size.width.toDouble() / size.height, s.glassesFovDeg.toDouble())
            val zoom = s.depth.coerceIn(StereoSettings.DEPTH_MIN, StereoSettings.DEPTH_MAX).toDouble()
            // Convergence in recorded pixels, as on screen: left image left, right image right.
            val shift = if (mono) 0.0 else (if (index == 0) 1.0 else -1.0) * s.convergencePx / size.width
            return EyeView(tanH, tanV, shift, zoom)
        }

        /**
         * Creates the recording's files and encoders and starts the IMU. The renderer then
         * feeds frames and the camera controller capture results. Throws if an encoder or
         * file can't be created.
         */
        fun start(
            context: Context,
            s: StereoSettings,
            rig: StereoRig,
            streamSize: Size,
            cameras: StereoRenderer.Cameras,
            cameraManager: CameraManager,
            extraInfo: JSONObject,
        ): Recording {
            val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val dir = File(root(context), name)
            check(dir.mkdirs()) { "Cannot create $dir" }
            val startClocks = Metadata.clocks()

            val geometry = StereoGeometry(listOf(cameras.left, cameras.right), s.useFactoryCalibration, cameras.displayRotation, s.rotationOverride)
            val mono = cameras.left === cameras.right
            val order = if (mono) listOf(0) else if (s.swapEyes) listOf(1, 0) else listOf(0, 1)
            val size = if (s.recordView == RecordView.RAW || geometry.imageXIsHorizontal) {
                streamSize
            } else {
                Size(streamSize.height, streamSize.width)
            }
            val bitrate = s.recordBitrateMbps * 1_000_000

            val outputs = mutableListOf<Output>()
            try {
                order.forEachIndexed { index, camera ->
                    val camDir = File(dir, "cam$index").apply { mkdirs() }
                    val frameColumns = mutableListOf("frame", "timestamp_ns", "render_ns")
                    if (s.recordView == RecordView.RECTIFIED) frameColumns += listOf("fx", "fy", "cx", "cy", "stab_qw", "stab_qx", "stab_qy", "stab_qz")
                    outputs += Output(
                        index = index,
                        camera = camera,
                        target = if (camera == 0) rig.left else rig.right,
                        size = size,
                        video = s.recordFormat.mime?.let {
                            VideoEncoder(File(camDir, "video.mp4"), it, size.width, size.height, rig.targetFps, bitrate)
                        },
                        jpeg = if (s.recordFormat == RecordFormat.JPEG) JpegWriter(File(camDir, "data"), size.width, size.height) else null,
                        frames = LineWriter(File(camDir, "frames.csv"), frameColumns.joinToString(",")),
                        results = LineWriter(File(camDir, "capture_results.csv"), RESULT_COLUMNS),
                    )
                }
            } catch (e: Exception) {
                outputs.forEach { o ->
                    o.video?.finish()
                    o.jpeg?.finish()
                    o.frames.close()
                    o.results.close()
                }
                dir.deleteRecursively()
                throw e
            }

            val sensorManager = context.getSystemService(SensorManager::class.java)
            val imu = ImuRecorder(sensorManager, File(dir, "imu"), ImuStream.parse(s.imuStreams), s.imuRate)

            val metadata = JSONObject().apply {
                put("format_version", 1)
                put("status", "recording")
                put("name", name)
                put("device", Metadata.device(context))
                put("clocks_at_start", startClocks)
                put("timestamps", timestampInfo(cameraManager, rig))
                put("recording", JSONObject().apply {
                    put("view", s.recordView.name)
                    put("format", s.recordFormat.name)
                    put("bitrate_bps_per_camera", if (s.recordFormat.mime != null) bitrate else JSONObject.NULL)
                    put("recorded_size", Metadata.value(size))
                    put("stream_size", Metadata.value(streamSize))
                    put("target_fps", rig.targetFps)
                    put("ae_target_fps_range", Metadata.value(rig.fpsRange))
                    put("exposure", if (s.manualExposure && rig.manualSensor) "manual" else "auto")
                    put("exposure_time_ns", if (s.manualExposure && rig.manualSensor) s.exposureTimeNs else JSONObject.NULL)
                    put("iso", if (s.manualExposure && rig.manualSensor) s.iso else JSONObject.NULL)
                    put("ae_compensation", if (s.manualExposure && rig.manualSensor) JSONObject.NULL else s.exposure)
                    put("focus", s.focusMode.name)
                    put("ois_disabled", rig.canDisableOis)
                    put("video_stabilization", "off")
                    put("hal_distortion_correction", if (rig.undistortInShader) "off" else "device default (see capture_results distortion_correction_mode)")
                    put("stabilization", s.stabilization.name)
                    put("settings", Metadata.settings(s))
                })
                put("rig", JSONObject().apply {
                    put("description", rig.description)
                    put("mode", rig.mode.name)
                    put("left", rig.left.label)
                    put("right", rig.right.label)
                    put("baseline_mm_from_poses", Metadata.value(rig.baselineMm))
                    put("max_fps_per_size", JSONObject().apply { rig.maxFps.forEach { (k, v) -> put(k.toString(), v) } })
                    put("rectification", geometry.summary)
                })
                put("cameras", JSONArray(outputs.map { cameraJson(it, s, rig, streamSize, cameras, geometry, cameraManager, mono) }))
                if (!mono) put("stereo", stereoJson(outputs, cameras, geometry, s))
                put("all_sensors", Metadata.allSensors(sensorManager))
                put("camera_characteristics", JSONObject().apply {
                    val ids = (rig.deviceIds + listOfNotNull(rig.left.physicalId, rig.right.physicalId)).distinct()
                    for (id in ids) {
                        put(id, runCatching { Metadata.characteristics(cameraManager.getCameraCharacteristics(id)) }
                            .getOrElse { JSONObject().put("error", it.toString()) })
                    }
                })
                put("extra", extraInfo)
                put("files", JSONObject().apply {
                    put("camN/video.mp4", "H.264/H.265, no B-frames; frame i = row i of camN/frames.csv")
                    put("camN/data/<timestamp_ns>.jpg", "JPEG mode: one file per stored frame")
                    put("camN/frames.csv", "stored frames: sensor timestamp (start of exposure), and for rectified recordings the intrinsics and stabilization rotation used")
                    put("camN/capture_results.csv", "every capture result of the camera: exposure, readout and rolling shutter times, ISO, focus, per-frame intrinsics")
                    put("imu/<stream>.csv", "one file per IMU stream")
                    put("settings.jsonl", "settings at start and after every change")
                })
            }
            File(dir, "metadata.json").writeText(metadata.toString(2))

            val recording = Recording(dir, s.recordView, s.recordFormat, outputs, imu, LineWriter(File(dir, "settings.jsonl")), metadata)
            recording.onSettingsChanged(s)
            imu.start()
            return recording
        }

        private const val RESULT_COLUMNS = "frame_number,timestamp_ns,readout_timestamp_ns,exposure_time_ns,frame_duration_ns," +
            "rolling_shutter_skew_ns,iso,post_raw_boost,ae_state,af_state,focus_distance_diopters,lens_state,ois_mode," +
            "distortion_correction_mode,fx,fy,cx,cy,skew,arrival_ns"

        private fun timestampInfo(manager: CameraManager, rig: StereoRig) = JSONObject().apply {
            val sources = rig.deviceIds.associateWith {
                runCatching { manager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) }.getOrNull()
            }
            val realtime = sources.values.all { it == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME }
            put("camera_timestamp_source", JSONObject().apply {
                sources.forEach { (id, v) -> put(id, if (v == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN") }
            })
            put("camera_clock", if (realtime) "CLOCK_BOOTTIME" else "unknown, usually CLOCK_MONOTONIC")
            put("imu_clock", "CLOCK_BOOTTIME")
            put("camera_timestamps_comparable_with_imu", realtime)
            put(
                "notes",
                "timestamp_ns of a frame is the start of exposure of its first row (SENSOR_TIMESTAMP). " +
                    "Mid-exposure of the middle row: timestamp_ns + exposure_time_ns/2 + rolling_shutter_skew_ns/2 " +
                    "(join with capture_results.csv on timestamp_ns). If the camera clock is not REALTIME, add " +
                    "clocks_at_start.boottime_minus_monotonic_ns to camera timestamps to put them on the IMU clock, " +
                    "and check arrival_ns - timestamp_ns in both files for consistency.",
            )
        }

        private fun cameraJson(
            o: Output,
            s: StereoSettings,
            rig: StereoRig,
            streamSize: Size,
            cameras: StereoRenderer.Cameras,
            geometry: StereoGeometry,
            manager: CameraManager,
            mono: Boolean,
        ) = JSONObject().apply {
            val model = if (o.camera == 0) cameras.left else cameras.right
            val chars = runCatching { manager.getCameraCharacteristics(o.target.physicalId ?: o.target.deviceId) }.getOrNull()
            put("name", o.name)
            put("eye", if (mono) "both" else if (o.index == 0) "left" else "right")
            put("camera", o.target.label)
            put("camera_id", o.target.deviceId)
            put("physical_camera_id", o.target.physicalId ?: JSONObject.NULL)
            put("rig_side", if (o.camera == 0) "left" else "right")
            put("size", Metadata.value(o.size))
            put("raw", JSONObject().apply {
                put("size", Metadata.value(streamSize))
                put("intrinsics_source", model.intrinsicsSource)
                put("fx", model.fx * streamSize.width)
                put("fy", model.fy * streamSize.height)
                put("cx", model.cx * streamSize.width)
                put("cy", model.cy * streamSize.height)
                val d = chars?.get(CameraCharacteristics.LENS_DISTORTION)
                put("lens_distortion_k1_k2_k3_p1_p2", Metadata.value(d))
                put("opencv_radtan_k1_k2_p1_p2_k3", d?.takeIf { it.size >= 5 }?.let { JSONArray(listOf(it[0], it[1], it[3], it[4], it[2]).map(Float::toDouble)) } ?: JSONObject.NULL)
                put("distortion_applies", if (rig.undistortInShader) true else "unknown: HAL correction left at device default")
                put("note", "Pinhole + radial-tangential model in pixels of the raw stream, row 0 at the top, image not rotated.")
            })
            put("device_from_camera_rotation", Metadata.matrix(Mat3.transpose(geometry.deviceToCamera[o.camera])))
            put("position_m", model.position?.let(Metadata::vector) ?: JSONObject.NULL)
            put("pose_reference", when (chars?.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
                CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
                CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
                CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
                CameraMetadata.LENS_POSE_REFERENCE_AUTOMOTIVE -> "AUTOMOTIVE"
                else -> JSONObject.NULL
            })
            put("frame_note", "Camera frame: x right, y down, z forward in the raw image. Device frame: Android sensor axes (same as the IMU).")
            if (s.recordView == RecordView.RECTIFIED) {
                val v = eyeView(geometry, s, o.index, o.size, mono)
                val k = geometry.rectifiedIntrinsics(o.size.width, o.size.height, v.tanH, v.tanV, v.shift, v.zoom)
                val cameraFromVirtual = geometry.cameraFromVirtual(o.camera, s.alignPitchDeg.toDouble(), s.alignRollDeg.toDouble())
                put("rectified", JSONObject().apply {
                    put("fx", k[0])
                    put("fy", k[1])
                    put("cx", k[2])
                    put("cy", k[3])
                    put("distortion", "none")
                    put("camera_from_virtual_rotation", Metadata.matrix(cameraFromVirtual))
                    put("device_from_virtual_rotation", Metadata.matrix(Mat3.mul(Mat3.transpose(geometry.deviceToCamera[o.camera]), cameraFromVirtual)))
                    put("note", "Intrinsics at the start; frames.csv has them per frame (zoom and convergence can change). " +
                        "With stabilization on, each frame is additionally rotated by stab_q (virtual ray = stab_q * output ray).")
                })
            }
        }

        /** Relative pose of cam1 in cam0's frame, raw and rectified. */
        private fun stereoJson(outputs: List<Output>, cameras: StereoRenderer.Cameras, geometry: StereoGeometry, s: StereoSettings) =
            JSONObject().apply {
                val (a, b) = outputs
                val models = listOf(cameras.left, cameras.right)
                val pa = models[a.camera].position
                val pb = models[b.camera].position
                val d0 = geometry.deviceToCamera[a.camera]
                val d1 = geometry.deviceToCamera[b.camera]
                put("raw_cam0_from_cam1_rotation", Metadata.matrix(Mat3.mul(d0, Mat3.transpose(d1))))
                if (pa != null && pb != null) {
                    val delta = doubleArrayOf(pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2])
                    put("baseline_m", Mat3.norm(delta))
                    put("raw_cam1_position_in_cam0_m", Metadata.vector(Mat3.mulVec(d0, delta)))
                    if (s.recordView == RecordView.RECTIFIED) {
                        val v0 = Mat3.mul(Mat3.transpose(geometry.cameraFromVirtual(a.camera, s.alignPitchDeg.toDouble(), s.alignRollDeg.toDouble())), d0)
                        put("rectified_cam1_position_in_cam0_m", Metadata.vector(Mat3.mulVec(v0, delta)))
                    }
                } else {
                    put("baseline_m", JSONObject.NULL)
                }
                put("note", "Rectified views share one orientation (up to the alignment correction), so their relative rotation is identity.")
            }
    }
}
