package com.xreal.stereoview.record

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.Nv12
import com.xreal.stereoview.util.PngGray
import com.xreal.stereoview.util.appendFixed
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A dataset from an OAK camera, in the EuRoC layout (see [Euroc]): the left and right mono
 * cameras in mav0/cam0 and mav0/cam1 (PNG by default, or JPEG or H.264 video), the color camera
 * in mav0/cam2 (by default its JPEGs as encoded on the camera; or RGB PNGs or H.264 video from
 * its raw frames), and the OAK's IMU as mav0/imu0, all on the camera's
 * clock (mid-exposure for images). sensor.yaml files come from the camera's factory
 * calibration. Extras: frames.csv per camera, the raw IMU streams and metadata.json with
 * depthai's device description and full calibration. The phone's IMU is not recorded: it does
 * not move with the OAK. Both mono images of a pair are stored under the left one's timestamp,
 * as EuRoC expects; each image's own is in frames.csv (sensor_timestamp_ns).
 */
class OakRecording private constructor(
    override val dir: File,
    private val cams: List<Cam>,
    private val accel: LineWriter?,
    private val gyro: LineWriter?,
    /** mav0/imu0/data.csv, written live from the OAK IMU. */
    private val imu0: LiveImu0?,
    private val settingsLog: SettingsLog,
    private val metadata: JSONObject,
    private val monoFormat: GrayImageFormat,
    private val jpegQuality: Int,
    private val rgbFormat: OakRgbFormat,
    /** Images that may wait for the writers (Settings → Recording buffers). */
    private val queueFrames: Int,
    writerThreads: Int,
) : ActiveRecording {
    private class Cam(val name: String, val dir: File, val extension: String, val data: LineWriter, val frames: LineWriter) {
        /** H.264 mono recordings: the camera's encoder; data.csv rows are written as frames reach the file. */
        var video: VideoEncoder? = null
        val pts = VideoEncoder.Companion.PtsMap()
        var stored = 0L
        var firstNs = 0L
        var lastNs = 0L
        var maxGapNs = 0L
    }

    override val startBoottimeNs: Long = SystemClock.elapsedRealtimeNanos()

    @Volatile
    override var error: String? = null

    private val writer = ThreadPoolExecutor(writerThreads, writerThreads, 1, TimeUnit.SECONDS, ArrayBlockingQueue(queueFrames)) { r -> Thread(r, "OakWriter") }

    /** Color frames that came in another form than [rgbFormat] needs (only while the stream restarts). */
    private val rgbMismatched = AtomicInteger()
    private val rgbBuffer = ThreadLocal<ByteArray>()

    @Volatile private var peakQueue = 0
    private val dropped = AtomicInteger()
    private val failed = AtomicInteger()
    private var lastAccelNs = Long.MIN_VALUE
    private var lastGyroNs = Long.MIN_VALUE
    private var imuSamples = 0L

    /** min(arrival on BOOTTIME − device timestamp): the clock offset plus the smallest delivery delay. */
    @Volatile private var boottimeMinusDevice = Long.MAX_VALUE

    @Volatile private var monotonicMinusDevice = 0L
    private val pairSpread = PairSpread()

    /** Both images under the left one's timestamp [t] (EuRoC pairs share timestamps). */
    fun onStereoFrame(frame: OakDevice.StereoFrame) {
        val t = frame.left.timestampNs
        pairSpread.add(frame.right.timestampNs - frame.left.timestampNs)
        for ((cam, mono) in cams.zip(listOf(frame.left, frame.right))) {
            offset(mono.arrivalNs, mono.timestampNs, mono.hostNs)
            val row = "$t,${mono.timestampNs},${mono.sequence},${mono.hostNs},${mono.arrivalNs},${mono.exposureUs},${mono.iso},${mono.startOfExposureNs}"
            val video = cam.video
            if (video != null) {
                cam.pts.put(t, t)
                if (video.submitGray(t, mono.image)) cam.frames.line(row) else dropped.incrementAndGet()
                continue
            }
            submit {
                GrayImages.write(File(cam.dir, "data/$t.${cam.extension}"), mono.image, mono.width, mono.height, monoFormat, jpegQuality)
                stored(cam, t, row)
            }
        }
    }

    fun onRgb(frame: OakDevice.Rgb) {
        val cam = cams.getOrNull(2) ?: return
        val t = frame.timestampNs
        offset(frame.arrivalNs, t, frame.hostNs)
        val row = "$t,$t,${frame.sequence},${frame.hostNs},${frame.arrivalNs},${frame.exposureUs},${frame.iso},${frame.startOfExposureNs}"
        if (frame.raw != (rgbFormat != OakRgbFormat.JPEG) || (frame.raw && frame.data.size < frame.width * frame.height * 3 / 2)) {
            rgbMismatched.incrementAndGet()
            return
        }
        val video = cam.video
        if (video != null) {
            cam.pts.put(t, t)
            if (video.submitNv12(t, frame.data)) cam.frames.line(row) else dropped.incrementAndGet()
            return
        }
        submit {
            val file = File(cam.dir, "data/$t.${cam.extension}")
            if (frame.raw) {
                val rgb = Nv12.toRgb(frame.data, frame.width, frame.height, rgbBuffer.get())
                rgbBuffer.set(rgb)
                PngGray.writeRgb(file, rgb, frame.width, frame.height)
            } else {
                file.writeBytes(frame.data)
            }
            stored(cam, t, row)
        }
    }

    @Synchronized
    fun onImu(samples: List<OakDevice.ImuSample>) {
        val a = accel ?: return
        val g = gyro ?: return
        for (s in samples) {
            // Each packet carries the latest sample of both sensors; write each sample once.
            if (s.accelNs > lastAccelNs) {
                a.line(StringBuilder(96).append(s.accelNs).append(',').append(s.arrivalNs).append(',').appendFixed(s.ax)
                    .append(',').appendFixed(s.ay).append(',').appendFixed(s.az).append(',').append(s.accelHostNs))
                imu0?.accel(s.accelNs, s.ax, s.ay, s.az)
                lastAccelNs = s.accelNs
            }
            if (s.gyroNs > lastGyroNs) {
                g.line(StringBuilder(96).append(s.gyroNs).append(',').append(s.arrivalNs).append(',').appendFixed(s.gx)
                    .append(',').appendFixed(s.gy).append(',').appendFixed(s.gz).append(',').append(s.gyroHostNs))
                imu0?.gyro(s.gyroNs, s.gx, s.gy, s.gz)
                lastGyroNs = s.gyroNs
                imuSamples++
            }
        }
    }

    private fun offset(arrivalNs: Long, deviceNs: Long, hostNs: Long) {
        val d = arrivalNs - deviceNs
        if (d < boottimeMinusDevice) boottimeMinusDevice = d
        monotonicMinusDevice = hostNs - deviceNs
    }

    private fun submit(task: () -> Unit) {
        try {
            writer.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "Writing a frame failed", e)
                    failed.incrementAndGet()
                }
            }
            writer.queue.size.let { if (it > peakQueue) peakQueue = it }
        } catch (e: RejectedExecutionException) {
            dropped.incrementAndGet()
        }
    }

    private fun stored(cam: Cam, timestampNs: Long, framesRow: String) {
        synchronized(cam) {
            if (cam.stored == 0L) cam.firstNs = timestampNs else cam.maxGapNs = maxOf(cam.maxGapNs, timestampNs - cam.lastNs)
            cam.lastNs = maxOf(cam.lastNs, timestampNs)
            cam.stored++
            cam.data.line("$timestampNs,$timestampNs.${cam.extension}")
            cam.frames.line(framesRow)
        }
    }

    override fun onSettingsChanged(s: StereoSettings) = settingsLog.log(s)

    override val backlog: String
        get() = "write queue ${writer.queue.size}/$queueFrames images (peak $peakQueue), dropped ${dropped.get()}"

    override fun writeErrors(): String? = (cams.flatMap { listOf(it.data, it.frames) } + listOfNotNull(accel, gyro))
        .firstNotNullOfOrNull { it.error }?.toString()

    /** Encoder thread: a frame of [cam] reached the video file. */
    private fun onVideoFrame(cam: Cam, ptsUs: Long) {
        val t = cam.pts.take(ptsUs)
        synchronized(cam) {
            if (cam.stored == 0L) cam.firstNs = t else cam.maxGapNs = maxOf(cam.maxGapNs, t - cam.lastNs)
            cam.lastNs = maxOf(cam.lastNs, t)
            cam.stored++
            cam.data.lineNow("$t,$t.png")
        }
    }

    override fun finish(): String {
        val end = Metadata.clocks()
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.MINUTES)
        cams.forEachIndexed { i, cam ->
            cam.video?.let {
                it.finish()
                metadata.getJSONArray("cameras").optJSONObject(i)?.put("encoder_settings", it.applied)
                    ?.apply { if (it.notWritten > 0) put("video_frames_not_written", it.notWritten) }
                it.error?.let { e -> error = error ?: "${cam.name} encoder: $e" }
            }
        }
        cams.forEach {
            it.data.close()
            it.frames.close()
            Euroc.sortByTimestamp(File(it.dir, "data.csv"))
            Euroc.sortByTimestamp(File(it.dir, "frames.csv"))
        }
        synchronized(this) {
            accel?.close()
            gyro?.close()
            imu0?.close()
        }
        settingsLog.close()
        val imu0 = writeImu0()

        metadata.put("status", if (error == null) "complete" else "stopped with error: $error")
        metadata.put("clocks_at_end", end)
        metadata.put("duration_s", (end.getLong("boottime_ns") - startBoottimeNs) / 1e9)
        metadata.put("stereo_pair_timestamps", pairSpread.json())
        metadata.put("imu0", imu0)
        metadata.put("oak_clock", JSONObject().apply {
            put("unit", "ns on the OAK's monotonic clock (cameras and IMU)")
            if (boottimeMinusDevice != Long.MAX_VALUE) put("boottime_minus_oak_ns_upper_bound", boottimeMinusDevice)
            put("monotonic_minus_oak_ns_depthai", monotonicMinusDevice)
            put("note", "boottime ≈ oak + offset. The first value is min(arrival − timestamp), i.e. the offset plus the smallest " +
                "USB delivery delay; the second is depthai's host-synced estimate on CLOCK_MONOTONIC (frames.csv host_ns).")
        })
        val camerasJson = metadata.getJSONArray("cameras")
        cams.forEachIndexed { i, cam ->
            camerasJson.getJSONObject(i).put("stats", JSONObject().apply {
                put("frames", cam.stored)
                put("first_timestamp_ns", cam.firstNs)
                put("last_timestamp_ns", cam.lastNs)
                if (cam.stored > 1) put("mean_fps", (cam.stored - 1) * 1e9 / (cam.lastNs - cam.firstNs))
                put("max_gap_ms", cam.maxGapNs / 1e6)
                cam.video?.let { v ->
                    put("encoder_frames_dropped", v.dropped.get())
                    put("encoder_backlog_peak_frames", v.peakPending)
                    put("encoder_backlog_capacity_frames", v.maxPending)
                }
            })
        }
        metadata.put("stats", JSONObject().apply {
            put("frames_dropped_by_writer", dropped.get())
            put("frames_failed", failed.get())
            if (rgbMismatched.get() > 0) put("color_frames_skipped_wrong_format", rgbMismatched.get())
            put("write_queue_capacity_frames", queueFrames)
            put("write_queue_peak_frames", peakQueue)
            put("oak_imu_gyro_samples", imuSamples)
        })
        File(dir, "metadata.json").writeText(metadata.toString(2))
        return buildString {
            append("Saved ${dir.name}: ")
            append(cams.joinToString { "${it.name} ${it.stored} frames" })
            if (imu0.optLong("samples") > 0) append(", imu0 %.0f Hz".format(imu0.optDouble("rate_hz")))
            if (dropped.get() > 0) append(", ${dropped.get()} frames dropped")
        }
    }

    private fun writeImu0(): JSONObject {
        val live = imu0 ?: return JSONObject().put("samples", 0).put("note", "OAK IMU off")
        writeImu0Yaml(dir, live.rateHz)
        return JSONObject().put("samples", live.rows).put("rate_hz", live.rateHz)
            .put("source", "mav0/imu0/oak_gyroscope.csv + oak_accelerometer.csv, written live")
    }

    companion object {
        private const val TAG = "OakRecording"

        /** Width of the color images (1280×800 or 1280×720). */
        const val RGB_WIDTH = 1280
        private const val FRAMES_HEADER = "timestamp_ns,sensor_timestamp_ns,sequence,host_monotonic_ns,arrival_ns,exposure_us,iso,start_of_exposure_ns"
        private const val IMU_HEADER = "timestamp_ns,arrival_ns,x,y,z,host_monotonic_ns"

        /** imu0/sensor.yaml; written at the start (rate 0 = not measured yet) and again at the end. */
        private fun writeImu0Yaml(dir: File, rateHz: Double) {
            Euroc.writeImu0Yaml(
                dir, listOf("OAK IMU (raw accelerometer + gyroscope), OAK clock. Body frame = this IMU."),
                "OAK gyroscope timestamps, accelerometer linearly interpolated", rateHz,
            )
        }

        fun start(context: Context, s: StereoSettings, info: JSONObject, extraInfo: JSONObject): OakRecording {
            val dir = RecordingStorage.createDir(context)
            val name = dir.name
            val mav = Euroc.mav(dir)
            val calibration = info.optJSONObject("calibration")
            val rgbFps = if (s.oakRgbFps > 0) s.oakRgbFps else s.oakFps
            val plan = OakDevice.FpsPlan.of(s.oakFps, rgbFps)
            val monoW = if (s.oakMonoHeight == 400) 640 else 1280
            val rgbRaw = s.oakRgbHeight > 0 && s.oakRgbFormat != OakRgbFormat.JPEG
            // Raw color frames (1.5 bytes per pixel) share the memory cap with the mono pair;
            // the camera's JPEGs are small next to the mono frames.
            val streams = if (rgbRaw) 3 else 2
            val monoBacklog = RecordingBuffers.frames(s.buffers.oakCameraS, s.oakFps, monoW.toLong() * s.oakMonoHeight, streams, min = VideoEncoder.MIN_PENDING)
            val rgbBacklog = if (rgbRaw) RecordingBuffers.frames(s.buffers.oakCameraS, rgbFps, RGB_WIDTH.toLong() * s.oakRgbHeight * 3 / 2, streams, min = VideoEncoder.MIN_PENDING) else monoBacklog
            class Spec(val name: String, val key: String, val ext: String, val width: Int, val height: Int, val fps: Int, val bitrateMbps: Int, val backlog: Int)
            val specs = listOfNotNull(
                Spec("cam0", "left", s.oakImageFormat.extension, monoW, s.oakMonoHeight, s.oakFps, s.oakVideoBitrateMbps, monoBacklog),
                Spec("cam1", "right", s.oakImageFormat.extension, monoW, s.oakMonoHeight, s.oakFps, s.oakVideoBitrateMbps, monoBacklog),
                if (s.oakRgbHeight > 0) Spec("cam2", "rgb", s.oakRgbFormat.extension, RGB_WIDTH, s.oakRgbHeight, rgbFps, s.oakRgbVideoBitrateMbps, rgbBacklog) else null,
            )
            lateinit var recording: OakRecording
            val cams = specs.map { spec ->
                val video = spec.ext == GrayImageFormat.H264.extension
                val camDir = File(mav, spec.name).apply { mkdirs() }
                if (!video) File(camDir, "data").mkdirs()
                calibration?.optJSONObject(spec.key)?.let { c -> runCatching { File(camDir, "sensor.yaml").writeText(cameraYaml(spec.name, spec.key, c, spec.fps)) } }
                Cam(spec.name, camDir, spec.ext, LineWriter(File(camDir, "data.csv"), Euroc.CAMERA_HEADER), LineWriter(File(camDir, "frames.csv"), FRAMES_HEADER)).also { cam ->
                    if (video) {
                        cam.video = VideoEncoder(
                            File(camDir, "data.mkv"), "video/avc", spec.width, spec.height, spec.fps, s.encoderOptions(spec.bitrateMbps),
                            grayInput = true, onWritten = { p -> recording.onVideoFrame(cam, p) }, grayBacklogFrames = spec.backlog,
                        )
                    }
                }
            }
            File(mav, "body.yaml").writeText(Euroc.bodyYaml("The body frame is the OAK's IMU."))
            Euroc.rawImuDir(dir).mkdirs()
            val accel = if (s.oakImuRateHz > 0) LineWriter(File(Euroc.rawImuDir(dir), "oak_accelerometer.csv"), IMU_HEADER) else null
            val gyro = if (s.oakImuRateHz > 0) LineWriter(File(Euroc.rawImuDir(dir), "oak_gyroscope.csv"), IMU_HEADER) else null
            val imu0 = if (s.oakImuRateHz > 0) {
                File(mav, "imu0").mkdirs()
                writeImu0Yaml(dir, 0.0)
                LiveImu0(File(mav, "imu0/data.csv"))
            } else {
                null
            }
            calibration?.optJSONObject("eeprom")?.let { File(dir, "oak_calibration.json").writeText(it.toString(2)) }

            val sensorManager = context.getSystemService(SensorManager::class.java)
            val metadata = JSONObject().apply {
                put("format_version", 2)
                put("layout", "EuRoC MAV (mav0/) plus extras")
                put("status", "recording")
                put("name", name)
                put("source", "OAK")
                put("device", Metadata.device(context))
                put("clocks_at_start", Metadata.clocks())
                put("oak", JSONObject(info.toString()).apply { remove("calibration") })
                put("oak_calibration_at_stream_size", JSONObject().apply {
                    calibration?.keys()?.forEach { k -> if (k != "eeprom") put(k, calibration.get(k)) }
                })
                put("timestamps", JSONObject().apply {
                    put("camera_clock", "OAK device clock, ns, middle of exposure")
                    put("oak_imu_clock", "OAK device clock, ns (same as the cameras)")
                    put("notes", "Left and right images with the same sequence number are a hardware-synchronized pair. Both are " +
                        "stored under the left image's timestamp (data.csv, file names); each image's own timestamp is frames.csv " +
                        "sensor_timestamp_ns (see stereo_pair_timestamps).")
                })
                put("recording", JSONObject().apply {
                    put("mono", when (s.oakImageFormat) {
                        GrayImageFormat.JPEG -> "JPEG quality ${s.grayJpegQuality} grayscale"
                        GrayImageFormat.H264 -> "H.264 in Matroska (data.mkv, crash-safe), ${s.oakVideoBitrateMbps} Mbit/s per camera"
                        GrayImageFormat.PNG -> "PNG 8-bit grayscale"
                    } + ", ${if (s.oakMonoHeight == 400) "640×400" else "1280×${s.oakMonoHeight}"}")
                    put("color", if (s.oakRgbHeight > 0) {
                        when (s.oakRgbFormat) {
                            OakRgbFormat.JPEG -> "JPEG quality ${s.oakJpegQuality}, encoded on the camera"
                            OakRgbFormat.PNG -> "PNG 8-bit RGB, from the camera's raw NV12 frames (full-range BT.601)"
                            OakRgbFormat.H264 -> "H.264 in Matroska (data.mkv, crash-safe), ${s.oakRgbVideoBitrateMbps} Mbit/s, encoded on the phone from the camera's raw NV12 frames"
                        } + ", $RGB_WIDTH×${s.oakRgbHeight}"
                    } else "off")
                    put("exposure", JSONArray(s.oakExposures.take(cams.size).mapIndexed { i, e -> e.toJson().put("camera", "cam$i") }))
                    put("fps", s.oakFps)
                    if (s.oakRgbHeight > 0) {
                        put("color_fps", rgbFps)
                        put("color_synchronized", plan.synchronized)
                        put("frame_rates", when {
                            !plan.synchronized -> "color at its own rate (not a multiple of the mono rate): not synchronized with the mono pair"
                            plan.monoEvery == 1 && plan.rgbEvery == 1 -> "all cameras at the same rate, synchronized"
                            else -> "all sensors at ${plan.monoSensorFps} fps, synchronized; the camera keeps 1 in ${plan.monoEvery} mono frames " +
                                "and 1 in ${plan.rgbEvery} color frames (by sequence number), so the kept frames stay synchronized"
                        })
                    }
                    put("oak_imu_gyroscope_hz_requested", s.oakImuRateHz)
                    put("oak_imu_accelerometer_hz_requested", OakDevice.accelRateFor(s.oakImuRateHz))
                    put("oak_imu_note", "Accelerometer and gyroscope have their own rates and timestamps: mav0/imu0/oak_*.csv hold each sensor's " +
                        "own samples; imu0 uses the gyroscope's timestamps with the accelerometer linearly interpolated to them.")
                    put("settings", Metadata.settings(s))
                })
                put("cameras", JSONArray(cams.map { JSONObject().put("name", it.name).put("format", it.extension) }))
                put("all_sensors", Metadata.allSensors(sensorManager))
                put("extra", extraInfo)
                put("files", JSONObject().apply {
                    put("mav0/cam0, cam1", "left and right mono cameras: data/<timestamp_ns>.${s.oakImageFormat.extension}, data.csv, sensor.yaml, frames.csv")
                    put("mav0/cam2", "color camera (if on): " + when (s.oakRgbFormat) {
                        OakRgbFormat.JPEG -> "data/<timestamp_ns>.jpg as encoded on the camera"
                        OakRgbFormat.PNG -> "data/<timestamp_ns>.png, RGB"
                        OakRgbFormat.H264 -> "data.mkv (frame i = row i of data.csv)"
                    })
                    put("mav0/camN/frames.csv", FRAMES_HEADER)
                    put("mav0/imu0", "EuRoC IMU from the OAK IMU: gyroscope timestamps, accelerometer interpolated (built at the end)")
                    put("mav0/imu0/oak_accelerometer.csv, oak_gyroscope.csv", IMU_HEADER + " (raw sensor values, m/s² and rad/s)")
                    put("oak_calibration.json", "the camera's full factory calibration (depthai EEPROM data)")
                    put(BasaltCalib.FILE, "Basalt calibration (cameras, T_imu_cam in the OAK IMU's frame): the custom one set in Settings, else the factory one")
                    put("settings.jsonl", "settings at start and after every change")
                })
            }
            val imuRate = if (s.oakImuRateHz > 0) s.oakImuRateHz.toDouble() else 200.0
            val basalt = runCatching { basaltFactory(calibration, specs.map { it.key }, imuRate) }
                .getOrElse { BasaltResult(null, listOf("converting the factory calibration failed: $it")) }
            metadata.put("basalt_calibration", BasaltCalib.write(context, dir, CameraSource.OAK, basalt.json)
                .put("factory_notes", JSONArray(basalt.notes)))
            File(dir, "metadata.json").writeText(metadata.toString(2))
            val queue = specs.filter { it.ext != GrayImageFormat.H264.extension }.sumOf { it.backlog }.coerceAtLeast(8)
            // RGB PNGs take several times longer than the mono ones: more writers.
            val threads = if (rgbRaw && s.oakRgbFormat == OakRgbFormat.PNG) 6 else 3
            recording = OakRecording(
                dir, cams, accel, gyro, imu0, SettingsLog(dir), metadata, s.oakImageFormat, s.grayJpegQuality,
                s.oakRgbFormat, queue, threads,
            )
            recording.onSettingsChanged(s)
            return recording
        }

        /** A Basalt calibration file, or why there is none, and notes on what it is made of. */
        class BasaltResult(val json: String?, val notes: List<String>)

        /**
         * The OAK's factory calibration (depthai, at the streamed sizes) of cameras [keys] in
         * Basalt's format: fisheye cameras as kb4, perspective ones as pinhole-radtan8 (depthai's
         * k1, k2, p1, p2, k3..k6; its thin-prism and tilt terms, usually zero, are dropped and
         * noted). Camera poses: the measured camera-to-IMU extrinsics; where the calibration lacks
         * them for any camera, the design ("spec") ones; else the cameras' poses relative to cam0
         * with cam0 as the IMU (identity), to be completed by a camera-IMU calibration.
         */
        fun basaltFactory(calibration: JSONObject?, keys: List<String>, imuRateHz: Double): BasaltResult {
            val notes = mutableListOf<String>()
            val cams = keys.map { key ->
                calibration?.optJSONObject(key) ?: return BasaltResult(null, listOf("no factory calibration of the $key camera"))
            }
            cams.forEachIndexed { i, c ->
                c.optJSONObject("errors")?.let { e -> e.keys().forEach { k -> notes += "cam$i $k unavailable: ${e.getString(k)}" } }
                if (!c.has("intrinsics")) return BasaltResult(null, notes + "cam$i has no intrinsics")
            }
            val poseKey = listOf("imu_from_camera", "imu_from_camera_spec", "left_from_camera").firstOrNull { k -> cams.all { it.has(k) } }
                ?: return BasaltResult(null, notes + "no camera poses (neither camera-IMU nor camera-camera extrinsics)")
            notes += when (poseKey) {
                "imu_from_camera" -> "T_imu_cam: depthai's measured camera-to-IMU extrinsics"
                "imu_from_camera_spec" -> "T_imu_cam: depthai's camera-to-IMU extrinsics with DESIGN (spec) translations: the measured ones are missing"
                else -> "T_imu_cam: NO camera-IMU extrinsics in the factory calibration; these are the cameras' poses relative to cam0 " +
                    "(cam0 = identity) and do NOT place them in imu0's frame: run a camera-IMU calibration"
            }
            val cameras = cams.mapIndexed { i, c ->
                val k = c.getJSONArray("intrinsics")
                val fx = k.getJSONArray(0).getDouble(0)
                val fy = k.getJSONArray(1).getDouble(1)
                val cx = k.getJSONArray(0).getDouble(2)
                val cy = k.getJSONArray(1).getDouble(2)
                val d = c.optJSONArray("distortion") ?: JSONArray()
                fun dist(j: Int) = d.optDouble(j, 0.0).takeUnless { it.isNaN() } ?: 0.0
                val m = c.getJSONArray(poseKey)
                val t = DoubleArray(16) { j -> if (j < 12) m.getJSONArray(j / 4).getDouble(j % 4) else if (j == 15) 1.0 else 0.0 }
                val (model, params) = if (c.optString("model") == "fisheye") {
                    "kb4" to BasaltCalib.kb4(fx, fy, cx, cy, DoubleArray(4) { dist(it) })
                } else {
                    val rest = (8 until d.length()).map { dist(it) }
                    if (rest.any { kotlin.math.abs(it) > 1e-9 }) notes += "cam$i dropped depthai terms s1..s4, tx, ty = $rest"
                    "pinhole-radtan8" to BasaltCalib.radtan8(fx, fy, cx, cy, dist(0), dist(1), dist(2), dist(3), dist(4), dist(5), dist(6), dist(7))
                }
                BasaltCalib.Camera(model, params, c.getInt("width"), c.getInt("height"), t)
            }
            val comment = "Luxonis OAK factory calibration (depthai), at the recorded image sizes. ${notes.joinToString(". ")}. " +
                "IMU noise values are placeholders. Factory values: recalibrate for best results."
            return BasaltResult(BasaltCalib.json(cameras, imuRateHz, comment), notes)
        }

        /**
         * EuRoC sensor.yaml from depthai's calibration of one camera at the streamed size.
         * Fisheye calibrations map to "equidistant"; perspective ones to "radial-tangential"
         * with k1, k2, p1, p2 only (depthai's full 14-coefficient model is in metadata.json).
         */
        private fun cameraYaml(name: String, key: String, c: JSONObject, fps: Int): String {
            val k = c.getJSONArray("intrinsics")
            val fx = k.getJSONArray(0).getDouble(0)
            val fy = k.getJSONArray(1).getDouble(1)
            val cx = k.getJSONArray(0).getDouble(2)
            val cy = k.getJSONArray(1).getDouble(2)
            val d = c.getJSONArray("distortion")
            val fisheye = c.optString("model") == "fisheye"
            val dist = if (fisheye) DoubleArray(4) { d.optDouble(it, 0.0) } else doubleArrayOf(d.optDouble(0, 0.0), d.optDouble(1, 0.0), d.optDouble(2, 0.0), d.optDouble(3, 0.0))
            val poseKey = listOf("imu_from_camera", "imu_from_camera_spec", "left_from_camera").first { c.has(it) }
            val m = c.getJSONArray(poseKey)
            val t = DoubleArray(16) { i -> if (i < 12) m.getJSONArray(i / 4).getDouble(i % 4) else if (i == 15) 1.0 else 0.0 }
            val notes = mutableListOf("$name ($key camera) of a Luxonis OAK, factory calibration at ${c.getInt("width")}x${c.getInt("height")}. Body frame = OAK IMU.")
            if (poseKey == "imu_from_camera_spec") notes += "T_BS uses depthai's DESIGN translation: the measured camera-IMU extrinsics are missing."
            if (poseKey == "left_from_camera") notes += "NO camera-IMU extrinsics in the factory calibration: T_BS is this camera's pose relative to cam0, NOT the IMU. Calibrate camera-IMU."
            if (!fisheye) {
                val rest = (4 until d.length()).map { d.getDouble(it) }
                if (rest.any { kotlin.math.abs(it) > 1e-9 }) {
                    notes += "depthai's model also has k3..k6, s1..s4, tx, ty = $rest (not representable here): recalibrate or use metadata.json."
                }
            }
            return Euroc.cameraYaml(
                notes, name, t, fps.toDouble(), c.getInt("width"), c.getInt("height"),
                doubleArrayOf(fx, fy, cx, cy), if (fisheye) "equidistant" else "radial-tangential", dist,
            )
        }
    }
}
