package com.xreal.stereoview.record

import android.content.Context
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.glasses.GlassesProtocol
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.PngGray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A dataset from the glasses: the left and right tracking cameras as lossless grayscale PNGs
 * (cam0 = left, cam1 = right, 480×640 upright) named by their timestamps, the glasses' IMU
 * when it was taken over, the Beam Pro's IMU streams, the glasses' factory calibration and
 * metadata.json.
 *
 * Camera and glasses-IMU timestamps are on the glasses' clock (ns since the glasses booted);
 * every row also has its arrival time on the Beam Pro's CLOCK_BOOTTIME, from which
 * metadata.json estimates the offset between the two clocks.
 */
class GlassesRecording private constructor(
    override val dir: File,
    private val cams: List<Cam>,
    private val glassesImu: LineWriter?,
    private val imu: ImuRecorder,
    private val settingsLog: LineWriter,
    private val metadata: JSONObject,
) : ActiveRecording {
    private class Cam(val name: String, val dir: File, val frames: LineWriter) {
        var stored = 0L
        var firstNs = 0L
        var lastNs = 0L
        var maxGapNs = 0L
    }

    override val startBoottimeNs: Long = SystemClock.elapsedRealtimeNanos()

    @Volatile
    override var error: String? = null

    /** PNG encoding off the decode thread; frames are dropped (and counted) when it falls behind. */
    private val encoder = ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS, ArrayBlockingQueue(12))
    private val dropped = AtomicInteger()
    private val failed = AtomicInteger()
    private var imuSamples = 0L
    private val cameraOffset = OffsetEstimate()
    private val imuOffset = OffsetEstimate()
    @Volatile
    private var calibration: String? = null

    /** Smallest (arrival − timestamp) seen: the clock offset plus the minimum delivery delay. */
    private class OffsetEstimate {
        var min = Long.MAX_VALUE
        var count = 0L

        @Synchronized
        fun add(arrivalNs: Long, timestampNs: Long) {
            min = minOf(min, arrivalNs - timestampNs)
            count++
        }
    }

    /** Decode thread: queues both images of a pair. */
    fun onStereoFrame(frame: GlassesDevice.StereoFrame) {
        for ((cam, eye) in cams.zip(listOf(frame.left, frame.right))) {
            cameraOffset.add(eye.arrivalNs, eye.timestampNs)
            try {
                encoder.execute { store(cam, eye) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                dropped.incrementAndGet()
            }
        }
    }

    private fun store(cam: Cam, eye: GlassesDevice.Eye) {
        try {
            PngGray.write(File(cam.dir, "data/${eye.timestampNs}.png"), eye.image, IMAGE_W, IMAGE_H)
        } catch (e: Exception) {
            Log.w(TAG, "Writing a frame failed", e)
            failed.incrementAndGet()
            return
        }
        synchronized(cam) {
            if (cam.stored == 0L) cam.firstNs = eye.timestampNs else cam.maxGapNs = maxOf(cam.maxGapNs, eye.timestampNs - cam.lastNs)
            cam.lastNs = maxOf(cam.lastNs, eye.timestampNs)
            cam.stored++
            cam.frames.line("${eye.timestampNs},${eye.sequence},${eye.arrivalNs},${eye.timestampNs}.png")
        }
    }

    /** USB thread: one glasses IMU sample. */
    fun onImu(s: GlassesProtocol.ImuSample, arrivalNs: Long) {
        val w = glassesImu ?: return
        imuOffset.add(arrivalNs, s.timestampNs)
        imuSamples++
        w.line(buildString(220) {
            append(s.timestampNs).append(',').append(arrivalNs)
            for (i in 0..2) append(',').append(s.gyroRadPerSec(i))
            for (i in 0..2) append(',').append(s.accelMps2(i))
            for (i in 0..2) append(',').append(s.magScaled(i))
            append(',').append("%.2f".format(Locale.US, s.temperatureC))
            for (v in s.gyro) append(',').append(v)
            for (v in s.accel) append(',').append(v)
            for (v in s.mag) append(',').append(v)
            append(',').append(s.gyroMultiplier).append(',').append(s.gyroDivisor)
            append(',').append(s.accelMultiplier).append(',').append(s.accelDivisor)
        })
    }

    fun onCalibration(json: String) {
        calibration = json
        runCatching { File(dir, "glasses_calibration.json").writeText(json) }
    }

    override fun onSettingsChanged(s: StereoSettings) {
        settingsLog.line(JSONObject().apply {
            put("boottime_ns", SystemClock.elapsedRealtimeNanos())
            put("settings", Metadata.settings(s))
        }.toString())
    }

    override fun finish(): String {
        val end = Metadata.clocks()
        encoder.shutdown()
        encoder.awaitTermination(30, TimeUnit.SECONDS)
        val imuSummary = imu.stop()
        cams.forEach { it.frames.close() }
        glassesImu?.close()
        settingsLog.close()

        metadata.put("status", if (error == null) "complete" else "stopped with error: $error")
        metadata.put("clocks_at_end", end)
        metadata.put("duration_s", (end.getLong("boottime_ns") - startBoottimeNs) / 1e9)
        metadata.put("imu", imuSummary)
        metadata.put("glasses_clock", JSONObject().apply {
            put("unit", "ns since the glasses booted (camera metadata row and IMU reports)")
            put("note", "boottime ≈ glasses_timestamp + offset; the estimates below are min(arrival − timestamp), " +
                "i.e. the offset plus the smallest USB delivery delay, so they overestimate the offset by about a millisecond.")
            if (cameraOffset.count > 0) put("boottime_minus_glasses_ns_from_camera", cameraOffset.min)
            if (imuOffset.count > 0) put("boottime_minus_glasses_ns_from_imu", imuOffset.min)
        })
        metadata.put("glasses_factory_calibration", calibration?.let { runCatching { JSONObject(it) }.getOrElse { _ -> it } } ?: JSONObject.NULL)
        val camerasJson = metadata.getJSONArray("cameras")
        cams.forEachIndexed { i, cam ->
            camerasJson.getJSONObject(i).put("stats", JSONObject().apply {
                put("frames", cam.stored)
                put("first_timestamp_ns", cam.firstNs)
                put("last_timestamp_ns", cam.lastNs)
                if (cam.stored > 1) put("mean_fps", (cam.stored - 1) * 1e9 / (cam.lastNs - cam.firstNs))
                put("max_gap_ms", cam.maxGapNs / 1e6)
            })
        }
        metadata.put("stats", JSONObject().apply {
            put("frames_dropped_by_encoder", dropped.get())
            put("frames_failed", failed.get())
            put("glasses_imu_samples", imuSamples)
        })
        File(dir, "metadata.json").writeText(metadata.toString(2))

        return buildString {
            append("Saved ${dir.name}: ")
            append(cams.joinToString { "${it.name} ${it.stored} frames" })
            if (glassesImu != null) {
                val seconds = (end.getLong("boottime_ns") - startBoottimeNs) / 1e9
                append(", glasses IMU %.0f Hz".format(imuSamples / seconds.coerceAtLeast(1e-3)))
            }
            if (dropped.get() > 0) append(", ${dropped.get()} frames dropped")
        }
    }

    companion object {
        private const val TAG = "GlassesRecording"
        private const val IMAGE_W = 480
        private const val IMAGE_H = 640

        fun start(context: Context, s: StereoSettings, device: UsbDevice, withImu: Boolean, extraInfo: JSONObject): GlassesRecording {
            val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val dir = File(Recording.root(context), name)
            check(dir.mkdirs()) { "Cannot create $dir" }
            val cams = listOf("cam0", "cam1").map { camName ->
                val camDir = File(dir, camName).apply { File(this, "data").mkdirs() }
                Cam(camName, camDir, LineWriter(File(camDir, "frames.csv"), "timestamp_ns,sequence,arrival_ns,file"))
            }
            val glassesImu = if (withImu) {
                File(dir, "imu").mkdirs()
                LineWriter(
                    File(dir, "imu/glasses_imu.csv"),
                    "timestamp_ns,arrival_ns,wx,wy,wz,ax,ay,az,mx,my,mz,temperature_c," +
                        "raw_gx,raw_gy,raw_gz,raw_ax,raw_ay,raw_az,raw_mx,raw_my,raw_mz,gyro_mul,gyro_div,accel_mul,accel_div",
                )
            } else {
                null
            }
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val imu = ImuRecorder(sensorManager, File(dir, "imu"), ImuStream.parse(s.imuStreams), s.imuRate)

            val metadata = JSONObject().apply {
                put("format_version", 1)
                put("status", "recording")
                put("name", name)
                put("source", "GLASSES")
                put("device", Metadata.device(context))
                put("clocks_at_start", Metadata.clocks())
                put("glasses", JSONObject().apply {
                    put("usb_id", "%04x:%04x".format(device.vendorId, device.productId))
                    put("product", device.productName ?: JSONObject.NULL)
                    put("serial", runCatching { device.serialNumber }.getOrNull() ?: JSONObject.NULL)
                    put("firmware_bcd", device.version)
                    put("imu_taken_over", withImu)
                })
                put("timestamps", JSONObject().apply {
                    put("camera_clock", "glasses clock, ns")
                    put("glasses_imu_clock", "glasses clock, ns (same as the cameras)")
                    put("beam_pro_imu_clock", "CLOCK_BOOTTIME (imu/<stream>.csv)")
                    put("notes", "Each camera image is timestamped by the glasses (metadata row of its USB frame). " +
                        "Left and right images with the same sequence number form a stereo pair.")
                })
                put("recording", JSONObject().apply {
                    put("view", "RAW")
                    put("format", "PNG (8-bit grayscale, lossless)")
                    put("size", JSONArray(listOf(IMAGE_W, IMAGE_H)))
                    put("fps_per_camera", 30)
                    put("image_orientation", "upright: left camera rotated 90° counter-clockwise, right 90° clockwise, as in Monado")
                    put("settings", Metadata.settings(s))
                })
                put("cameras", JSONArray(listOf(
                    JSONObject().put("name", "cam0").put("eye", "left").put("size", JSONArray(listOf(IMAGE_W, IMAGE_H))),
                    JSONObject().put("name", "cam1").put("eye", "right").put("size", JSONArray(listOf(IMAGE_W, IMAGE_H))),
                )))
                put("reference_calibration", referenceCalibration())
                put("all_sensors", Metadata.allSensors(sensorManager))
                put("extra", extraInfo)
                put("files", JSONObject().apply {
                    put("camN/data/<timestamp_ns>.png", "camera images, glasses clock")
                    put("camN/frames.csv", "timestamp_ns (glasses), sequence, arrival_ns (BOOTTIME), file")
                    put("imu/glasses_imu.csv", "glasses IMU (if taken over): SI units in the IMU's own axes, uncalibrated, plus raw values")
                    put("imu/<stream>.csv", "Beam Pro IMU streams, CLOCK_BOOTTIME")
                    put("glasses_calibration.json", "the glasses' factory calibration (if the IMU was taken over)")
                    put("settings.jsonl", "settings at start and after every change")
                })
            }
            File(dir, "metadata.json").writeText(metadata.toString(2))
            val recording = GlassesRecording(dir, cams, glassesImu, imu, LineWriter(File(dir, "settings.jsonl")), metadata)
            recording.onSettingsChanged(s)
            imu.start()
            return recording
        }

        /**
         * The Basalt calibration of one Air 2 Ultra that Monado's driver hardcodes: a starting
         * point for calibrating your own unit, not its calibration.
         */
        private fun referenceCalibration() = JSONObject().apply {
            put("source", "Monado xreal_air_tracker.c (Basalt calibration of another unit); recalibrate with Kalibr/Basalt")
            put("camera_model", "kb4 (Kannala-Brandt), on the upright 480×640 images")
            put("cam0", JSONObject("""{"fx":240.57122490048506,"fy":240.74854215383074,"cx":238.9491196368791,"cy":320.29607073555209,""" +
                """"k1":0.020856425660087508,"k2":0.0299260962615731,"k3":-0.032168347423742738,"k4":0.007262692844289708,""" +
                """"imu_q_cam_xyzw":[0.9973474875662636,0.0018642996931548873,-0.044823515329715259,0.057318111495431169],""" +
                """"imu_p_cam":[-0.04919995913471168,-0.001209836612281824,-0.009887014172375066]}"""))
            put("cam1", JSONObject("""{"fx":241.69265557166814,"fy":241.87529140953999,"cx":239.12284466054269,"cy":319.1242983084969,""" +
                """"k1":0.019200339780075235,"k2":0.03074245961432518,"k3":-0.03197361414068182,"k4":0.006952063686239072,""" +
                """"imu_q_cam_xyzw":[0.9972690586706283,-0.0024372791609311458,0.04218557174298931,0.06057113029494838],""" +
                """"imu_p_cam":[0.0878646673959822,-0.002347085430219617,-0.00992990350848626]}"""))
            put("imu_frame_note", "Monado flips the IMU's y and z axes (T_A_B = diag(1,-1,-1)) to get x right, y up, z forward")
        }
    }
}
