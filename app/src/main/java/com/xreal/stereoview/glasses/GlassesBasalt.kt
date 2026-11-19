package com.xreal.stereoview.glasses

import com.xreal.stereoview.record.BasaltCalib
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The glasses' factory camera calibration (SLAM_camera.device_1/device_2 of the JSON the
 * glasses send) as a Basalt calibration of the recorded images (upright 480×640) in the frame
 * of mav0/imu0. Nothing reads these values yet (Monado parses them, then uses a hardcoded
 * calibration of another unit), so their conventions are assumptions, written into the file:
 *
 *  - kc = k1..k6, p1, p2, s1..s4 of a Fisheye624 model (Basalt "fisheye624"; Monado treats
 *    the first 8 as Fisheye62);
 *  - fc, cc are in pixels of the camera's own image, of size "resolution"; if that is 640×480
 *    (as the sensor sends it) the model is turned with the image: left 90° counter-clockwise,
 *    right 90° clockwise;
 *  - imu_q_cam (x, y, z, w) and imu_p_cam (meters) place the camera in the IMU's sensor axes,
 *    turned into imu0's axes (sensor x, y, z → −x, z, y).
 *
 * Without SLAM_camera (e.g. the IMU wasn't taken over, so no calibration was read) it falls
 * back to Monado's reference calibration of another unit ([Kb4Camera.REFERENCE]).
 */
object GlassesBasalt {
    /** Sensor IMU axes to imu0's ([GlassesImuCalibration]): (x, y, z) → (−x, z, y). */
    private val IMU0_FROM_SENSOR = doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0)

    class Result(val json: String, val info: JSONObject)

    fun factory(calibrationJson: String?, imuRateHz: Double): Result {
        val slam = runCatching { JSONObject(calibrationJson!!).getJSONObject("SLAM_camera") }.getOrNull()
        val devices = listOf("device_1", "device_2").map { slam?.optJSONObject(it) }
        if (devices.any { it == null || it.optJSONArray("kc")?.length() != 12 || it.optJSONArray("fc") == null }) {
            return reference(imuRateHz, if (calibrationJson == null) "no factory calibration received (take over the glasses IMU to read it)"
            else "the factory calibration has no usable SLAM_camera entry")
        }
        val info = JSONObject().put("source", "glasses factory calibration (SLAM_camera)")
        val notes = mutableListOf<String>()
        val cameras = devices.mapIndexed { i, d -> camera(d!!, right = i == 1, notes) }
        // How far the factory poses are from the reference calibration (another unit): a check of the assumed frames.
        val checks = cameras.mapIndexed { i, c ->
            val ref = Kb4Camera.REFERENCE[i]
            "cam$i %.1f° from the reference unit's orientation".format(angleDeg(rotation(c.tImuCam), ref.imuFromCamera))
        }
        val baseline = (0..2).map { cameras[1].tImuCam[it * 4 + 3] - cameras[0].tImuCam[it * 4 + 3] }.let { d -> sqrt(d.sumOf { it * it }) }
        info.put("check", checks.joinToString("; ") + "; baseline %.1f mm (reference unit: 137.4 mm)".format(baseline * 1000))
        val comment = "XREAL glasses factory calibration (SLAM_camera), converted by the app with ASSUMED conventions: kc = Fisheye624 " +
            "k1..k6 p1 p2 s1..s4; fc/cc in pixels of 'resolution', turned upright with the image; imu_q_cam (xyzw), imu_p_cam (m) in " +
            "IMU sensor axes, turned into imu0 axes (x,y,z -> -x,z,y). ${notes.joinToString(" ")} Check: ${info.getString("check")}. " +
            "Not a calibration of this setup: recalibrate."
        return Result(BasaltCalib.json(cameras, imuRateHz, comment), info)
    }

    private fun reference(imuRateHz: Double, why: String): Result {
        val cameras = Kb4Camera.REFERENCE.map { c ->
            BasaltCalib.Camera("kb4", BasaltCalib.kb4(c.fx, c.fy, c.cx, c.cy, c.k), Kb4Camera.WIDTH, Kb4Camera.HEIGHT,
                BasaltCalib.transform(c.imuFromCamera, c.positionInImu))
        }
        val comment = "Monado's hardcoded Basalt calibration of ANOTHER Air 2 Ultra ($why): a starting point only, recalibrate."
        return Result(BasaltCalib.json(cameras, imuRateHz, comment), JSONObject().put("source", "Monado's reference calibration of another unit ($why)"))
    }

    private fun camera(d: JSONObject, right: Boolean, notes: MutableList<String>): BasaltCalib.Camera {
        fun arr(name: String, n: Int) = DoubleArray(n) { d.getJSONArray(name).getDouble(it) }
        val kc = arr("kc", 12)
        val fc = arr("fc", 2)
        val cc = arr("cc", 2)
        val size = d.optJSONArray("resolution")?.let { intArrayOf(it.optInt(0), it.optInt(1)) } ?: intArrayOf(640, 480)
        val q = d.optJSONArray("imu_q_cam")?.let { a -> DoubleArray(4) { a.getDouble(it) } } ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val p = d.optJSONArray("imu_p_cam")?.let { a -> DoubleArray(3) { a.getDouble(it) } } ?: DoubleArray(3)
        val sensorFromCam = BasaltCalib.transform(BasaltCalib.matrix(q[0], q[1], q[2], q[3]), p)
        val imu0FromSensor = BasaltCalib.transform(IMU0_FROM_SENSOR, DoubleArray(3))
        val k = kc.copyOfRange(0, 6)
        var tan = kc.copyOfRange(6, 8)
        var prism = kc.copyOfRange(8, 12)
        var fx = fc[0]; var fy = fc[1]; var cx = cc[0]; var cy = cc[1]
        var w = size[0]; var h = size[1]
        // camera_from_upright: the camera's own axes from the upright image's.
        var camFromUpright = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        if (w == 640 && h == 480) {
            val (pp1, pp2) = tan[0] to tan[1]
            val (s1, s2, s3, s4) = prism
            if (!right) {
                // Upright u' = v, v' = W−1−u: x' = y, y' = −x.
                cx = cc[1]; cy = w - 1 - cc[0]; fx = fc[1]; fy = fc[0]
                tan = doubleArrayOf(pp2, -pp1)
                prism = doubleArrayOf(s3, s4, -s1, -s2)
                camFromUpright = doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            } else {
                // Upright u' = H−1−v, v' = u: x' = −y, y' = x.
                cx = h - 1 - cc[1]; cy = cc[0]; fx = fc[1]; fy = fc[0]
                tan = doubleArrayOf(-pp2, pp1)
                prism = doubleArrayOf(-s3, -s4, s1, s2)
                camFromUpright = doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            }
            w = 480; h = 640
            if (!right) notes += "Factory resolution 640x480 turned upright to 480x640."
        } else if (!(w == 480 && h == 640)) {
            notes += "Factory resolution ${w}x$h is neither 640x480 nor 480x640: used as is."
        }
        val t = BasaltCalib.multiply(BasaltCalib.multiply(imu0FromSensor, sensorFromCam), BasaltCalib.transform(camFromUpright, DoubleArray(3)))
        return BasaltCalib.Camera("fisheye624", BasaltCalib.fisheye624(fx, fy, cx, cy, k, tan, prism), w, h, t)
    }

    private fun rotation(t: DoubleArray) = doubleArrayOf(t[0], t[1], t[2], t[4], t[5], t[6], t[8], t[9], t[10])

    /** Angle of the rotation between two rotation matrices (row-major), degrees. */
    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        // trace(aᵀb)
        val trace = (0..2).sumOf { i -> (0..2).sumOf { j -> a[j * 3 + i] * b[j * 3 + i] } }
        return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
    }
}
