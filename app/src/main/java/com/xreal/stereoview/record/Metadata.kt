package com.xreal.stereoview.record

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import com.xreal.stereoview.ui.StereoSettings
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Converts device, camera and sensor information to JSON for a recording's metadata.json. */
object Metadata {
    fun device(context: Context) = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("soc_manufacturer", Build.SOC_MANUFACTURER)
        put("soc_model", Build.SOC_MODEL)
        put("fingerprint", Build.FINGERPRINT)
        put("build_id", Build.ID)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        put("app_package", context.packageName)
        put("app_version", app.versionName)
        put("app_version_code", app.longVersionCode)
    }

    /**
     * The clocks at one instant. Camera timestamps use CLOCK_BOOTTIME when the camera's
     * timestamp source is REALTIME, else usually CLOCK_MONOTONIC; IMU timestamps use BOOTTIME.
     */
    fun clocks() = JSONObject().apply {
        val boot = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        val wall = System.currentTimeMillis()
        put("boottime_ns", boot)
        put("monotonic_ns", mono)
        put("boottime_minus_monotonic_ns", boot - mono)
        put("wall_clock_ms", wall)
        put("wall_clock_utc", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(wall)))
    }

    /** Every key the camera reports, by its Camera2 name. */
    fun characteristics(chars: CameraCharacteristics) = JSONObject().apply {
        for (key in chars.keys) {
            val v = try {
                @Suppress("UNCHECKED_CAST")
                chars.get(key as CameraCharacteristics.Key<Any>)
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            put(key.name, value(v))
        }
        put("physical_camera_ids", JSONArray(chars.physicalCameraIds.toList()))
    }

    fun sensor(s: Sensor) = JSONObject().apply {
        put("name", s.name)
        put("vendor", s.vendor)
        put("version", s.version)
        put("type", s.type)
        put("string_type", s.stringType)
        put("id", s.id)
        put("max_range", s.maximumRange.toDouble())
        put("resolution", s.resolution.toDouble())
        put("power_ma", s.power.toDouble())
        put("min_delay_us", s.minDelay)
        put("max_delay_us", s.maxDelay)
        if (s.minDelay > 0) put("max_rate_hz", 1e6 / s.minDelay)
        put("fifo_reserved_events", s.fifoReservedEventCount)
        put("fifo_max_events", s.fifoMaxEventCount)
        put("reporting_mode", when (s.reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special_trigger"
            else -> s.reportingMode.toString()
        })
        put("wake_up", s.isWakeUpSensor)
        put("dynamic", s.isDynamicSensor)
        put("additional_info_supported", s.isAdditionalInfoSupported)
        put("highest_direct_report_rate_level", s.highestDirectReportRateLevel)
        put("direct_channel_memory_file", s.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE))
        put("direct_channel_hardware_buffer", s.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER))
    }

    fun allSensors(manager: SensorManager) = JSONArray(manager.getSensorList(Sensor.TYPE_ALL).map(::sensor))

    /** Every field of [s], enums by name. */
    fun settings(s: StereoSettings) = JSONObject().apply {
        for (field in StereoSettings::class.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val v = field.get(s)
            put(field.name, if (v is Enum<*>) v.name else value(v))
        }
    }

    /** Row-major 3×3 matrix as nested arrays. */
    fun matrix(m: DoubleArray) = JSONArray(List(3) { r -> JSONArray(List(3) { c -> m[3 * r + c] }) })

    fun vector(v: DoubleArray) = JSONArray(v.toList())

    fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is String -> v
        is Float -> if (v.isFinite()) v.toDouble() else v.toString()
        is Double -> if (v.isFinite()) v else v.toString()
        is Number -> v.toLong()
        is IntArray -> JSONArray(v.toList())
        is LongArray -> JSONArray(v.toList())
        is FloatArray -> JSONArray(v.map { value(it) })
        is DoubleArray -> JSONArray(v.map { value(it) })
        is BooleanArray -> JSONArray(v.toList())
        is ByteArray -> JSONArray(v.map { it.toInt() })
        is Array<*> -> JSONArray(v.map(::value))
        is Collection<*> -> JSONArray(v.map(::value))
        is Size -> JSONArray(listOf(v.width, v.height))
        is SizeF -> JSONArray(listOf(v.width.toDouble(), v.height.toDouble()))
        is Rect -> JSONArray(listOf(v.left, v.top, v.right, v.bottom))
        is Point -> JSONArray(listOf(v.x, v.y))
        is Range<*> -> JSONArray(listOf(value(v.lower), value(v.upper)))
        is Rational -> v.toDouble()
        is ColorSpaceTransform -> JSONArray(List(3) { r -> JSONArray(List(3) { c -> v.getElement(c, r).toDouble() }) })
        is BlackLevelPattern -> JSONArray(List(2) { r -> JSONArray(List(2) { c -> v.getOffsetForIndex(c, r) }) })
        is RggbChannelVector -> JSONArray(listOf(v.red, v.greenEven, v.greenOdd, v.blue).map { it.toDouble() })
        is StreamConfigurationMap -> streamMap(v)
        else -> v.toString()
    }

    /** Output sizes per format with their minimum frame durations, plus high-speed modes. */
    private fun streamMap(map: StreamConfigurationMap) = JSONObject().apply {
        fun sizes(sizes: Array<Size>?, minDuration: (Size) -> Long, stall: (Size) -> Long) = JSONArray(
            sizes.orEmpty().map { size ->
                JSONObject().apply {
                    put("size", JSONArray(listOf(size.width, size.height)))
                    val d = runCatching { minDuration(size) }.getOrDefault(0L)
                    put("min_frame_duration_ns", d)
                    if (d > 0) put("max_fps", 1e9 / d)
                    runCatching { stall(size) }.getOrNull()?.takeIf { it > 0 }?.let { put("stall_ns", it) }
                }
            }
        )
        val outputs = JSONObject()
        for (format in map.outputFormats) {
            outputs.put(
                formatName(format),
                sizes(map.getOutputSizes(format), { map.getOutputMinFrameDuration(format, it) }, { map.getOutputStallDuration(format, it) }),
            )
        }
        outputs.put(
            "SurfaceTexture",
            sizes(
                map.getOutputSizes(SurfaceTexture::class.java),
                { map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) },
                { map.getOutputStallDuration(SurfaceTexture::class.java, it) },
            ),
        )
        put("outputs", outputs)
        put("input_formats", JSONArray(map.inputFormats.map(::formatName)))
        put("high_speed_video", JSONArray(map.highSpeedVideoSizes.map { size ->
            JSONObject().apply {
                put("size", JSONArray(listOf(size.width, size.height)))
                put("fps_ranges", JSONArray(map.getHighSpeedVideoFpsRangesFor(size).map { value(it) }))
            }
        }))
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
        ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
        ImageFormat.HEIC -> "HEIC"
        ImageFormat.Y8 -> "Y8"
        ImageFormat.NV21 -> "NV21"
        ImageFormat.YV12 -> "YV12"
        else -> "0x%x".format(format)
    }
}
