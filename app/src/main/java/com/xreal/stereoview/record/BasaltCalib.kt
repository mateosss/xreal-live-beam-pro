package com.xreal.stereoview.record

import android.content.Context
import android.net.Uri
import com.xreal.stereoview.ui.CameraSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * Calibration files in Basalt's format (basalt::Calibration as cereal JSON, "value0": …),
 * written into every recording as [FILE]: the camera intrinsics, each camera's pose in the IMU
 * frame of mav0/imu0 (T_imu_cam), resolutions and IMU noise. By default they come from the
 * device's factory calibration ([FACTORY_FILE] holds that one too when a custom file is used);
 * a custom file per device ([Custom]) replaces it.
 *
 * IMU biases are zero: every imu0 is already in calibrated units (the glasses' factory bias and
 * scale applied, the OAK's and phone's as their drivers report them). Noise values are Basalt's
 * EuRoC defaults: placeholders.
 */
object BasaltCalib {
    const val FILE = "calibration.bslt.json"
    const val FACTORY_FILE = "calibration.factory.bslt.json"

    /** One camera: Basalt model name and its parameters in Basalt's order, image size, T_imu_cam (4×4 row-major, meters). */
    class Camera(val model: String, val params: List<Pair<String, Double>>, val width: Int, val height: Int, val tImuCam: DoubleArray)

    fun pinhole(fx: Double, fy: Double, cx: Double, cy: Double) = listOf("fx" to fx, "fy" to fy, "cx" to cx, "cy" to cy)

    /** Kannala-Brandt (OpenCV fisheye, EuRoC "equidistant"). */
    fun kb4(fx: Double, fy: Double, cx: Double, cy: Double, k: DoubleArray) =
        pinhole(fx, fy, cx, cy) + (0 until 4).map { "k${it + 1}" to k.getOrElse(it) { 0.0 } }

    /** OpenCV's rational model (k1, k2, p1, p2, k3, k4, k5, k6); rpmax −1 lets Basalt estimate it. */
    fun radtan8(fx: Double, fy: Double, cx: Double, cy: Double, k1: Double, k2: Double, p1: Double, p2: Double, k3: Double,
                k4: Double = 0.0, k5: Double = 0.0, k6: Double = 0.0) =
        pinhole(fx, fy, cx, cy) + listOf("k1" to k1, "k2" to k2, "p1" to p1, "p2" to p2, "k3" to k3, "k4" to k4, "k5" to k5, "k6" to k6, "rpmax" to -1.0)

    /** Fisheye with 6 radial, 2 tangential and 4 thin-prism terms (Meta's / Project Aria's Fisheye624). */
    fun fisheye624(fx: Double, fy: Double, cx: Double, cy: Double, k: DoubleArray, p: DoubleArray, s: DoubleArray) =
        pinhole(fx, fy, cx, cy) + (0 until 6).map { "k${it + 1}" to k[it] } + listOf("p1" to p[0], "p2" to p[1]) +
            (0 until 4).map { "s${it + 1}" to s[it] }

    /** The calibration file: [cameras] in order cam0, cam1, …; [comment] says where the values come from. */
    fun json(cameras: List<Camera>, imuRateHz: Double, comment: String): String {
        val v = JSONObject()
        v.put("comment", comment)
        v.put("T_imu_cam", JSONArray(cameras.map { pose(it.tImuCam) }))
        v.put("intrinsics", JSONArray(cameras.map { c ->
            JSONObject().put("camera_type", c.model).put("intrinsics", JSONObject().apply { c.params.forEach { (k, x) -> put(k, x) } })
        }))
        v.put("resolution", JSONArray(cameras.map { JSONArray(listOf(it.width, it.height)) }))
        v.put("calib_accel_bias", JSONArray(List(9) { 0.0 }))
        v.put("calib_gyro_bias", JSONArray(List(12) { 0.0 }))
        v.put("imu_update_rate", if (imuRateHz > 0) imuRateHz else 200.0)
        v.put("accel_noise_std", JSONArray(List(3) { 0.016 }))
        v.put("gyro_noise_std", JSONArray(List(3) { 0.000282 }))
        v.put("accel_bias_std", JSONArray(List(3) { 0.001 }))
        v.put("gyro_bias_std", JSONArray(List(3) { 0.0001 }))
        v.put("cam_time_offset_ns", 0)
        v.put("vignette", JSONArray())
        return JSONObject().put("value0", v).toString(4)
    }

    /**
     * Writes [FILE] into [dir]: the custom calibration of [source] if one was imported, else the
     * factory one. Returns a description for metadata.json. Never throws.
     */
    fun write(context: Context, dir: File, source: CameraSource, factory: String?): JSONObject {
        val info = JSONObject()
        try {
            val custom = Custom.file(context, source).takeIf { it.exists() }
            if (custom != null) {
                custom.copyTo(File(dir, FILE), overwrite = true)
                factory?.let { File(dir, FACTORY_FILE).writeText(it) }
                info.put("source", "custom calibration imported in Settings (${Custom.name(context, source) ?: custom.name})")
                if (factory != null) info.put("factory", FACTORY_FILE)
            } else if (factory != null) {
                File(dir, FILE).writeText(factory)
                info.put("source", "factory calibration (not a calibration of this recording's setup: recalibrate)")
            } else {
                info.put("source", "none: no calibration available")
            }
        } catch (e: Exception) {
            info.put("error", e.toString())
        }
        return info
    }

    /** Basalt pose {px, py, pz, qx, qy, qz, qw} of a 4×4 row-major transform. */
    fun pose(t: DoubleArray): JSONObject {
        val q = quaternion(doubleArrayOf(t[0], t[1], t[2], t[4], t[5], t[6], t[8], t[9], t[10]))
        return JSONObject().put("px", t[3]).put("py", t[7]).put("pz", t[11])
            .put("qx", q[0]).put("qy", q[1]).put("qz", q[2]).put("qw", q[3])
    }

    /** Unit quaternion (x, y, z, w; w ≥ 0) of a rotation matrix (row-major). */
    fun quaternion(r: DoubleArray): DoubleArray {
        val trace = r[0] + r[4] + r[8]
        val q = if (trace > 0) {
            val s = sqrt(trace + 1.0) * 2
            doubleArrayOf((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, 0.25 * s)
        } else if (r[0] > r[4] && r[0] > r[8]) {
            val s = sqrt(1.0 + r[0] - r[4] - r[8]) * 2
            doubleArrayOf(0.25 * s, (r[1] + r[3]) / s, (r[2] + r[6]) / s, (r[7] - r[5]) / s)
        } else if (r[4] > r[8]) {
            val s = sqrt(1.0 + r[4] - r[0] - r[8]) * 2
            doubleArrayOf((r[1] + r[3]) / s, 0.25 * s, (r[5] + r[7]) / s, (r[2] - r[6]) / s)
        } else {
            val s = sqrt(1.0 + r[8] - r[0] - r[4]) * 2
            doubleArrayOf((r[2] + r[6]) / s, (r[5] + r[7]) / s, 0.25 * s, (r[3] - r[1]) / s)
        }
        val n = sqrt(q.sumOf { it * it }) * if (q[3] < 0) -1 else 1
        return DoubleArray(4) { q[it] / n }
    }

    /** Rotation matrix (row-major) of a unit quaternion x, y, z, w. */
    fun matrix(x: Double, y: Double, z: Double, w: Double): DoubleArray = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
    )

    /** a·b for 4×4 row-major transforms. */
    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
        (0 until 4).sumOf { k -> a[i / 4 * 4 + k] * b[k * 4 + i % 4] }
    }

    /** A 4×4 transform from a 3×3 rotation and a translation. */
    fun transform(r: DoubleArray, t: DoubleArray) = doubleArrayOf(
        r[0], r[1], r[2], t[0], r[3], r[4], r[5], t[1], r[6], r[7], r[8], t[2], 0.0, 0.0, 0.0, 1.0,
    )

    /**
     * Calibration files imported by the user, one per device ([CameraSource]): recordings of that
     * device carry it as [FILE] instead of the factory calibration.
     */
    object Custom {
        private fun dir(context: Context) = File(context.filesDir, "calibrations").apply { mkdirs() }
        fun file(context: Context, source: CameraSource) = File(dir(context), "${source.name.lowercase()}.bslt.json")
        private fun nameFile(context: Context, source: CameraSource) = File(dir(context), "${source.name.lowercase()}.name")

        /** The imported file's original name, if a custom calibration is set. */
        fun name(context: Context, source: CameraSource): String? =
            if (file(context, source).exists()) runCatching { nameFile(context, source).readText() }.getOrNull() ?: "custom" else null

        /** Checks [text] is a Basalt calibration; returns a short description of it, or throws with what is wrong. */
        fun validate(text: String): String {
            val v = JSONObject(text).optJSONObject("value0") ?: throw IllegalArgumentException("no \"value0\" object: not a Basalt calibration")
            val poses = v.optJSONArray("T_imu_cam") ?: throw IllegalArgumentException("no T_imu_cam")
            val intrinsics = v.optJSONArray("intrinsics") ?: throw IllegalArgumentException("no intrinsics")
            val resolution = v.optJSONArray("resolution") ?: throw IllegalArgumentException("no resolution")
            if (poses.length() == 0 || poses.length() != intrinsics.length() || resolution.length() != intrinsics.length()) {
                throw IllegalArgumentException("T_imu_cam, intrinsics and resolution must list the same cameras")
            }
            return (0 until intrinsics.length()).joinToString(", ") { i ->
                val r = resolution.getJSONArray(i)
                "cam$i ${intrinsics.getJSONObject(i).optString("camera_type")} ${r.optInt(0)}×${r.optInt(1)}"
            }
        }

        /** Imports the file at [uri] for [source]; returns a description of it, or throws if it isn't a Basalt calibration. */
        fun import(context: Context, source: CameraSource, uri: Uri, displayName: String?): String {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("cannot read the file")
            val description = validate(text)
            file(context, source).writeText(text)
            nameFile(context, source).writeText(displayName ?: uri.lastPathSegment ?: "custom")
            return description
        }

        fun clear(context: Context, source: CameraSource) {
            file(context, source).delete()
            nameFile(context, source).delete()
        }
    }
}
