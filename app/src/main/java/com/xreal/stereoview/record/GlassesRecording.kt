package com.xreal.stereoview.record

import android.content.Context
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.glasses.GlassesBasalt
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.glasses.GlassesImuCalibration
import com.xreal.stereoview.glasses.GlassesProtocol
import com.xreal.stereoview.glasses.Kb4Camera
import com.xreal.stereoview.ui.BitrateScale
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.util.PngGray
import com.xreal.stereoview.util.appendFixed
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
 * A dataset from the glasses, in the EuRoC layout (see [Euroc]): the left and right tracking
 * cameras as lossless grayscale PNGs in mav0/cam0 and mav0/cam1 (480×640 upright), the
 * glasses' IMU as mav0/imu0 when it was taken over, plus frames.csv with each frame's sequence
 * number, own timestamp, arrival time and metadata bytes, the full glasses IMU reports, the
 * glasses' factory calibration and metadata.json. The phone's IMU is not recorded: it does not
 * move with the glasses.
 *
 * Camera and glasses-IMU timestamps are on the glasses' clock (ns since the glasses booted);
 * every row also has its arrival time on the Beam Pro's CLOCK_BOOTTIME, from which
 * metadata.json estimates the offset between the two clocks. Both images of a stereo pair are
 * stored under the left image's timestamp, as EuRoC expects; each image's own timestamp is in
 * frames.csv (sensor_timestamp_ns), and metadata.json sums up how far apart they are.
 */
class GlassesRecording private constructor(
    private val appContext: Context,
    override val dir: File,
    private val cams: List<CameraStream>,
    private val glassesImu: LineWriter?,
    private val imu0: LineWriter?,
    private val settingsLog: SettingsLog,
    private val metadata: JSONObject,
    private val format: GrayImageFormat,
    private val jpegQuality: Int,
    /** Image writers, with room for several seconds of frames (Settings → Recording buffers). */
    private val writers: FrameWriterPool,
) : ActiveRecording {
    override val startBoottimeNs: Long = SystemClock.elapsedRealtimeNanos()

    @Volatile
    override var error: String? = null

    private var imuSamples = 0L
    private var imuFirstNs = 0L
    private var imuLastNs = 0L
    private val cameraOffset = OffsetEstimate()
    private val imuOffset = OffsetEstimate()
    private val pairSpread = PairSpread()

    @Volatile
    private var calibration: String? = null

    /** Converts samples to imu0 as Monado does: factory calibration, Monado's IMU axes. */
    @Volatile private var imuCalibration = GlassesImuCalibration.parse(null)

    /** imu0 rows written before the factory calibration arrived (axes only). */
    private var imuWithoutFactory = 0L

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

    /** Decode thread: queues both images of a pair, under the left image's timestamp. */
    fun onStereoFrame(frame: GlassesDevice.StereoFrame) {
        val t = frame.left.timestampNs
        pairSpread.add(frame.right.timestampNs - frame.left.timestampNs)
        for ((cam, eye) in cams.zip(listOf(frame.left, frame.right))) {
            cameraOffset.add(eye.arrivalNs, eye.timestampNs)
            val row = framesRow(t, eye)
            if (cam.video != null) {
                if (!cam.submitVideo(t, row) { it.submitGray(t, eye.image) }) writers.countDropped()
            } else {
                writers.submit {
                    GrayImages.write(cam.imageFile(t), eye.image, IMAGE_W, IMAGE_H, format, jpegQuality)
                    cam.imageStored(t, row)
                }
            }
        }
    }

    private fun framesRow(t: Long, eye: GlassesDevice.Eye) =
        "$t,${eye.timestampNs},${eye.sequence},${eye.arrivalNs},${eye.meta.joinToString("") { "%02x".format(it) }}"

    /** USB thread: one glasses IMU sample. */
    fun onImu(s: GlassesProtocol.ImuSample, arrivalNs: Long) {
        val w = glassesImu ?: return
        imuOffset.add(arrivalNs, s.timestampNs)
        if (imuSamples == 0L) imuFirstNs = s.timestampNs
        imuLastNs = s.timestampNs
        imuSamples++
        imu0?.let { out ->
            val cal = imuCalibration
            if (!cal.factory) imuWithoutFactory++
            val g = cal.gyro(s)
            val a = cal.accel(s)
            out.line(buildString(120) {
                append(s.timestampNs)
                for (v in g) append(',').appendFixed(v)
                for (v in a) append(',').appendFixed(v)
            })
        }
        w.line(buildString(220) {
            append(s.timestampNs).append(',').append(arrivalNs)
            for (i in 0..2) append(',').appendFixed(s.gyroRadPerSec(i))
            for (i in 0..2) append(',').appendFixed(s.accelMps2(i))
            for (i in 0..2) append(',').appendFixed(s.magScaled(i))
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
        imuCalibration = GlassesImuCalibration.parse(json)
        runCatching { File(dir, "glasses_calibration.json").writeText(json) }
        writeBasalt(GlassesDevice.IMU_RATE_HZ.toDouble())
    }

    /** calibration.bslt.json: the custom one, or the factory one from [calibration] (see [GlassesBasalt]). */
    private fun writeBasalt(imuRateHz: Double) {
        val factory = GlassesBasalt.factory(calibration, imuRateHz)
        metadata.put("basalt_calibration", BasaltCalib.write(appContext, dir, CameraSource.GLASSES, factory.json)
            .put("factory_conversion", factory.info))
    }

    override fun onSettingsChanged(s: StereoSettings) = settingsLog.log(s)

    override val backlog: String get() = writers.backlog

    override fun writeErrors(): String? = (cams.map { it.writeError } + listOfNotNull(imu0, glassesImu).map { it.error })
        .firstNotNullOfOrNull { it }?.toString()

    override fun finish(): String {
        val end = Metadata.clocks()
        writers.finish()
        val camerasJson = metadata.getJSONArray("cameras")
        cams.forEachIndexed { i, cam ->
            cam.finish()?.let { e -> error = error ?: "${cam.name} encoder: $e" }
            cam.encoderJson(camerasJson.getJSONObject(i))
        }
        glassesImu?.close()
        imu0?.close()
        settingsLog.close()
        if (imu0 != null) {
            val rate = Euroc.meanRateHz(imuSamples, imuFirstNs, imuLastNs)
            writeImu0Yaml(dir, rate)
            writeBasalt(if (rate > 0) rate else GlassesDevice.IMU_RATE_HZ.toDouble())
        } else {
            writeBasalt(GlassesDevice.IMU_RATE_HZ.toDouble())
        }

        metadata.put("status", if (error == null) "complete" else "stopped with error: $error")
        metadata.put("clocks_at_end", end)
        metadata.put("duration_s", (end.getLong("boottime_ns") - startBoottimeNs) / 1e9)
        metadata.put("stereo_pair_timestamps", pairSpread.json())
        metadata.put("glasses_clock", JSONObject().apply {
            put("unit", "ns since the glasses booted (camera metadata row and IMU reports)")
            put("note", "boottime ≈ glasses_timestamp + offset; the estimates below are min(arrival − timestamp), " +
                "i.e. the offset plus the smallest USB delivery delay, so they overestimate the offset by about a millisecond.")
            if (cameraOffset.count > 0) put("boottime_minus_glasses_ns_from_camera", cameraOffset.min)
            if (imuOffset.count > 0) put("boottime_minus_glasses_ns_from_imu", imuOffset.min)
        })
        if (imu0 != null) metadata.put("imu0_conversion", imuCalibration.toJson().apply {
            put("as", "Monado xreal_air driver (read_sample_and_apply_calibration): gyroscope rotated by accel_q_gyro, " +
                "SI units (g = ${GlassesImuCalibration.GRAVITY}), 32-bit floats, 10 decimals, pre-biased axes (x,y,z)→(−x,−z,−y), minus bias, times scale, " +
                "post-biased axes (x,y,z)→(x,−y,−z). Net axes: sensor (x,y,z) → (−x,z,y).")
            put("rows_without_factory_calibration", imuWithoutFactory)
        })
        metadata.put("glasses_factory_calibration", calibration?.let { runCatching { JSONObject(it) }.getOrElse { _ -> it } } ?: JSONObject.NULL)
        cams.forEachIndexed { i, cam -> camerasJson.getJSONObject(i).put("stats", cam.statsJson()) }
        metadata.put("stats", writers.statsJson("frames_dropped_by_encoder").put("glasses_imu_samples", imuSamples))
        File(dir, "metadata.json").writeText(metadata.toString(2))

        return buildString {
            append("Saved ${dir.name}: ")
            append(cams.joinToString { "${it.name} ${it.stored} frames" })
            if (glassesImu != null) {
                val seconds = (end.getLong("boottime_ns") - startBoottimeNs) / 1e9
                append(", glasses IMU %.0f Hz".format(imuSamples / seconds.coerceAtLeast(1e-3)))
            }
            if (writers.dropped > 0) append(", ${writers.dropped} frames dropped")
        }
    }

    companion object {
        private const val TAG = "GlassesRecording"
        private const val IMAGE_W = 480
        private const val IMAGE_H = 640
        private const val FRAMES_HEADER = "timestamp_ns,sensor_timestamp_ns,sequence,arrival_ns,meta_hex"

        /** imu0/sensor.yaml; written at the start (rate 0 = not measured yet) and again at the end. */
        private fun writeImu0Yaml(dir: File, rateHz: Double) {
            Euroc.writeImu0Yaml(
                dir,
                listOf(
                    "XREAL Air 2 Ultra IMU as Monado's driver outputs it: factory bias, scale and misalignment applied, in Monado's IMU axes",
                    "(sensor x,y,z -> -x,z,y), the frame of Monado's reference calibration. Raw values: imu0/glasses_imu.csv.",
                    "Same clock as the cameras (glasses clock). Body frame = this IMU.",
                ),
                "glasses IMU", rateHz,
            )
        }

        fun start(context: Context, s: StereoSettings, device: UsbDevice, withImu: Boolean, extraInfo: JSONObject): GlassesRecording {
            val dir = RecordingStorage.createDir(context)
            val name = dir.name
            val mav = Euroc.mav(dir)
            val video = s.glassesImageFormat == GrayImageFormat.H264
            // Per camera; both cameras share the memory cap.
            val backlog = RecordingBuffers.frames(s.buffers.glassesCameraS, 30, IMAGE_W.toLong() * IMAGE_H, streams = 2, min = VideoEncoder.MIN_PENDING)
            val cams = listOf("cam0", "cam1").mapIndexed { i, camName ->
                val camDir = File(mav, camName).apply { mkdirs() }
                File(camDir, "sensor.yaml").writeText(cameraYaml(i))
                CameraStream(camName, camDir, s.glassesImageFormat.extension, FRAMES_HEADER).apply {
                    if (video) {
                        this.video = VideoEncoder(
                            File(camDir, "data.mkv"), "video/avc", IMAGE_W, IMAGE_H, 30, s.encoderOptions(s.glassesVideoBitrateMbps),
                            grayInput = true, onWritten = ::videoFrameWritten, grayBacklogFrames = backlog,
                        )
                    }
                }
            }
            File(mav, "body.yaml").writeText(Euroc.bodyYaml("The body frame is the glasses IMU."))
            val imu0 = if (withImu) {
                File(mav, "imu0").mkdirs()
                writeImu0Yaml(dir, 0.0)
                LineWriter(File(mav, "imu0/data.csv"), Euroc.IMU_HEADER)
            } else {
                null
            }
            val glassesImu = if (withImu) {
                Euroc.rawImuDir(dir).mkdirs()
                LineWriter(
                    File(Euroc.rawImuDir(dir), "glasses_imu.csv"),
                    "timestamp_ns,arrival_ns,wx,wy,wz,ax,ay,az,mx,my,mz,temperature_c," +
                        "raw_gx,raw_gy,raw_gz,raw_ax,raw_ay,raw_az,raw_mx,raw_my,raw_mz,gyro_mul,gyro_div,accel_mul,accel_div",
                )
            } else {
                null
            }
            val sensorManager = context.getSystemService(SensorManager::class.java)

            val metadata = JSONObject().apply {
                put("format_version", 2)
                put("layout", "EuRoC MAV (mav0/) plus extras")
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
                    put("notes", "Each camera image is timestamped by the glasses (metadata row of its USB frame). " +
                        "Left and right images with the same sequence number form a stereo pair; both are stored under the " +
                        "left image's timestamp (data.csv, file names), each image's own is frames.csv sensor_timestamp_ns " +
                        "(see stereo_pair_timestamps).")
                })
                put("recording", JSONObject().apply {
                    put("view", "RAW")
                    put("format", when (s.glassesImageFormat) {
                        GrayImageFormat.JPEG -> "JPEG quality ${s.grayJpegQuality} (grayscale as luma, neutral chroma)"
                        GrayImageFormat.H264 -> "H.264 in Matroska (mav0/camN/data.mkv, crash-safe), ${BitrateScale.format(s.glassesVideoBitrateMbps)} per camera; frame i = row i of data.csv"
                        GrayImageFormat.PNG -> "PNG (8-bit grayscale, lossless)"
                    })
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
                    put("mav0/camN/data/<timestamp_ns>.${s.glassesImageFormat.extension}", "camera images (8-bit grayscale), glasses clock")
                    put("mav0/camN/data.csv", "EuRoC: timestamp, file name")
                    put("mav0/camN/sensor.yaml", "EuRoC camera description from Monado's reference calibration (another unit; recalibrate)")
                    put("mav0/camN/frames.csv", "timestamp_ns (the pair's, as data.csv), sensor_timestamp_ns (this image's own, glasses clock), " +
                        "sequence, arrival_ns (BOOTTIME), first 64 bytes of the frame's metadata row")
                    put("mav0/imu0/data.csv", "EuRoC IMU: the glasses IMU (if taken over) converted as Monado does (factory calibration applied, Monado's IMU axes; see imu0_conversion), glasses clock")
                    put("mav0/imu0/glasses_imu.csv", "glasses IMU (if taken over), as the sensor reports it: SI units in the sensor's own axes without calibration, magnetometer, temperature and raw integer values")
                    put("glasses_calibration.json", "the glasses' factory calibration (if the IMU was taken over)")
                    put(BasaltCalib.FILE, "Basalt calibration (cameras, T_imu_cam in imu0's frame): the custom one set in Settings, else the factory one converted (see basalt_calibration)")
                    put("settings.jsonl", "settings at start and after every change")
                })
            }
            File(dir, "metadata.json").writeText(metadata.toString(2))
            val recording = GlassesRecording(context.applicationContext, dir, cams, glassesImu, imu0, SettingsLog(dir), metadata, s.glassesImageFormat, s.grayJpegQuality,
                FrameWriterPool(3, 2 * backlog, "GlassesWriter"),
            )
            recording.onSettingsChanged(s)
            recording.writeBasalt(GlassesDevice.IMU_RATE_HZ.toDouble())
            return recording
        }

        /** EuRoC sensor.yaml of camera [index] from the reference calibration (pinhole + equidistant = KB4). */
        private fun cameraYaml(index: Int): String {
            val c = Kb4Camera.REFERENCE[index]
            return Euroc.cameraYaml(
                listOf(
                    "cam$index (${if (index == 0) "left" else "right"}) tracking camera of XREAL Air 2 Ultra glasses, upright 480x640 grayscale.",
                    "Intrinsics and T_BS are Monado's reference Basalt calibration of ANOTHER unit: a starting point only.",
                    "Calibrate your glasses (e.g. Kalibr, pinhole-equi). Body frame = glasses IMU.",
                ),
                "cam$index", Euroc.transform(c.imuFromCamera, c.positionInImu), 30.0,
                Kb4Camera.WIDTH, Kb4Camera.HEIGHT, doubleArrayOf(c.fx, c.fy, c.cx, c.cy), "equidistant", c.k,
            )
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
