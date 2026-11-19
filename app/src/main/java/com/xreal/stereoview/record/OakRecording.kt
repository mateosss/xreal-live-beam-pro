package com.xreal.stereoview.record

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.PngGray
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
 * cameras as lossless grayscale PNGs in mav0/cam0 and mav0/cam1, the color camera's JPEGs (as
 * encoded on the camera) in mav0/cam2, and the OAK's IMU as mav0/imu0, all on the camera's
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
    private val settingsLog: LineWriter,
    private val metadata: JSONObject,
    private val monoFormat: GrayImageFormat,
    private val jpegQuality: Int,
    /** Images that may wait for the writers (Settings → Recording buffers). */
    private val queueFrames: Int,
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

    private val writer = ThreadPoolExecutor(3, 3, 1, TimeUnit.SECONDS, ArrayBlockingQueue(queueFrames)) { r -> Thread(r, "OakWriter") }
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
        submit {
            File(cam.dir, "data/${frame.timestampNs}.jpg").writeBytes(frame.jpeg)
            stored(cam, frame.timestampNs, "${frame.timestampNs},${frame.timestampNs},${frame.sequence},${frame.hostNs},${frame.arrivalNs},${frame.exposureUs},${frame.iso},")
        }
    }

    @Synchronized
    fun onImu(samples: List<OakDevice.ImuSample>) {
        val a = accel ?: return
        val g = gyro ?: return
        for (s in samples) {
            // Each packet carries the latest sample of both sensors; write each sample once.
            if (s.accelNs > lastAccelNs) {
                a.line("${s.accelNs},${s.arrivalNs},${s.ax},${s.ay},${s.az},${s.accelHostNs}")
                imu0?.accel(s.accelNs, s.ax, s.ay, s.az)
                lastAccelNs = s.accelNs
            }
            if (s.gyroNs > lastGyroNs) {
                g.line("${s.gyroNs},${s.arrivalNs},${s.gx},${s.gy},${s.gz},${s.gyroHostNs}")
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

    override fun onSettingsChanged(s: StereoSettings) {
        settingsLog.line(JSONObject().apply {
            put("boottime_ns", SystemClock.elapsedRealtimeNanos())
            put("settings", Metadata.settings(s))
        }.toString())
    }

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
            .put("source", "imu/oak_gyroscope.csv + imu/oak_accelerometer.csv, written live")
    }

    companion object {
        private const val TAG = "OakRecording"
        private const val FRAMES_HEADER = "timestamp_ns,sensor_timestamp_ns,sequence,host_monotonic_ns,arrival_ns,exposure_us,iso,start_of_exposure_ns"
        private const val IMU_HEADER = "timestamp_ns,arrival_ns,x,y,z,host_monotonic_ns"

        /** imu0/sensor.yaml; written at the start (rate 0 = not measured yet) and again at the end. */
        private fun writeImu0Yaml(dir: File, rateHz: Double) {
            File(Euroc.mav(dir), "imu0/sensor.yaml").writeText(Euroc.imuYaml(
                listOf("OAK IMU (raw accelerometer + gyroscope), OAK clock. Body frame = this IMU.") +
                    if (rateHz == 0.0) listOf("rate_hz is measured when the recording ends (0 = the recording did not end normally).") else emptyList(),
                "OAK gyroscope timestamps, accelerometer linearly interpolated",
                Euroc.transform(com.xreal.stereoview.util.Mat3.identity(), null), rateHz,
            ))
        }

        fun start(context: Context, s: StereoSettings, info: JSONObject, extraInfo: JSONObject): OakRecording {
            val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val dir = File(Recording.root(context), name)
            check(dir.mkdirs()) { "Cannot create $dir" }
            val mav = Euroc.mav(dir)
            val calibration = info.optJSONObject("calibration")
            val rgbFps = if (s.oakRgbFps > 0) s.oakRgbFps else s.oakFps
            val plan = OakDevice.FpsPlan.of(s.oakFps, rgbFps)
            val specs = listOfNotNull(
                Triple("cam0", "left", s.oakImageFormat.extension),
                Triple("cam1", "right", s.oakImageFormat.extension),
                if (s.oakRgbHeight > 0) Triple("cam2", "rgb", "jpg") else null,
            )
            lateinit var recording: OakRecording
            val monoW = if (s.oakMonoHeight == 400) 640 else 1280
            // Per mono camera; both share the memory cap (color JPEGs are small next to them).
            val backlog = RecordingBuffers.frames(s.buffers.oakCameraS, s.oakFps, monoW.toLong() * s.oakMonoHeight, streams = 2, min = VideoEncoder.MIN_PENDING)
            val cams = specs.map { (camName, key, ext) ->
                val video = ext == GrayImageFormat.H264.extension
                val camDir = File(mav, camName).apply { mkdirs() }
                if (!video) File(camDir, "data").mkdirs()
                calibration?.optJSONObject(key)?.let { c -> runCatching { File(camDir, "sensor.yaml").writeText(cameraYaml(camName, key, c, if (key == "rgb") rgbFps else s.oakFps)) } }
                Cam(camName, camDir, ext, LineWriter(File(camDir, "data.csv"), Euroc.CAMERA_HEADER), LineWriter(File(camDir, "frames.csv"), FRAMES_HEADER)).also { cam ->
                    if (video) {
                        cam.video = VideoEncoder(
                            File(camDir, "data.mkv"), "video/avc", monoW, s.oakMonoHeight, s.oakFps, s.encoderOptions(s.oakVideoBitrateMbps),
                            grayInput = true, onWritten = { p -> recording.onVideoFrame(cam, p) }, grayBacklogFrames = backlog,
                        )
                    }
                }
            }
            File(mav, "body.yaml").writeText(Euroc.bodyYaml("The body frame is the OAK's IMU."))
            File(dir, "imu").mkdirs()
            val accel = if (s.oakImuRateHz > 0) LineWriter(File(dir, "imu/oak_accelerometer.csv"), IMU_HEADER) else null
            val gyro = if (s.oakImuRateHz > 0) LineWriter(File(dir, "imu/oak_gyroscope.csv"), IMU_HEADER) else null
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
                    put("color", if (s.oakRgbHeight > 0) "JPEG 1280×${s.oakRgbHeight}, quality ${s.oakJpegQuality}, encoded on the camera" else "off")
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
                    put("oak_imu_note", "Accelerometer and gyroscope have their own rates and timestamps: imu/oak_*.csv hold each sensor's " +
                        "own samples; imu0 uses the gyroscope's timestamps with the accelerometer linearly interpolated to them.")
                    put("settings", Metadata.settings(s))
                })
                put("cameras", JSONArray(cams.map { JSONObject().put("name", it.name).put("format", it.extension) }))
                put("all_sensors", Metadata.allSensors(sensorManager))
                put("extra", extraInfo)
                put("files", JSONObject().apply {
                    put("mav0/cam0, cam1", "left and right mono cameras: data/<timestamp_ns>.${s.oakImageFormat.extension}, data.csv, sensor.yaml, frames.csv")
                    put("mav0/cam2", "color camera (if on): data/<timestamp_ns>.jpg as encoded on the camera")
                    put("mav0/camN/frames.csv", FRAMES_HEADER)
                    put("mav0/imu0", "EuRoC IMU from the OAK IMU: gyroscope timestamps, accelerometer interpolated (built at the end)")
                    put("imu/oak_accelerometer.csv, oak_gyroscope.csv", IMU_HEADER + " (raw sensor values, m/s² and rad/s)")
                    put("oak_calibration.json", "the camera's full factory calibration (depthai EEPROM data)")
                    put("settings.jsonl", "settings at start and after every change")
                })
            }
            File(dir, "metadata.json").writeText(metadata.toString(2))
            recording = OakRecording(dir, cams, accel, gyro, imu0, LineWriter(File(dir, "settings.jsonl")), metadata, s.oakImageFormat, s.grayJpegQuality, specs.size * backlog)
            recording.onSettingsChanged(s)
            return recording
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
            val m = c.getJSONArray("imu_from_camera")
            val t = DoubleArray(16) { i -> if (i < 12) m.getJSONArray(i / 4).getDouble(i % 4) else if (i == 15) 1.0 else 0.0 }
            val notes = mutableListOf("$name ($key camera) of a Luxonis OAK, factory calibration at ${c.getInt("width")}x${c.getInt("height")}. Body frame = OAK IMU.")
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
