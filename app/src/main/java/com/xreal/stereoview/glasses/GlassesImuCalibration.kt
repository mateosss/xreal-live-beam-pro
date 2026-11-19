package com.xreal.stereoview.glasses

import org.json.JSONObject

/**
 * The glasses IMU's factory calibration (IMU.device_1 of the calibration JSON the glasses send)
 * and the conversion Monado's xreal_air driver applies to every sample before its tracker and
 * SLAM see it (xreal_air_hmd.c, read_sample_and_apply_calibration), reproduced step by step so
 * recordings match Monado's and calibrations made from Monado data apply:
 *
 *  1. raw × multiplier / divisor: gyroscope in °/s, accelerometer in g (sensor axes).
 *  2. Gyroscope rotated by accel_q_gyro (the gyroscope's misalignment to the accelerometer).
 *  3. To SI units: rad/s and m/s² (g = 9.8066, Monado's value).
 *  4. "Pre-biased" axes (x, y, z) → (−x, −z, −y): the frame the factory biases and scales are in.
 *  5. Minus the factory bias, times the factory per-axis scale.
 *  6. "Post-biased" axes (x, y, z) → (x, −y, −z): Monado's IMU frame, the one its reference
 *     Basalt calibration (T_imu_cam) and calibrations of Monado recordings are expressed in.
 *
 * In 32-bit floats, in Monado's order of operations, so values match its output to the last
 * printed digit (up to float rounding).
 *
 * Steps 4 and 6 together are a proper rotation (180° about (0, 1, 1)/√2): sensor (x, y, z) →
 * (−x, z, y). Missing fields leave that step out (bias 0, scale 1, identity rotation).
 */
class GlassesImuCalibration(
    val accelBias: FloatArray = FloatArray(3),
    val gyroBias: FloatArray = FloatArray(3),
    val scaleAccel: FloatArray = floatArrayOf(1f, 1f, 1f),
    val scaleGyro: FloatArray = floatArrayOf(1f, 1f, 1f),
    /** accel_q_gyro as x, y, z, w (the order of the JSON). */
    val accelQGyro: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    /** Whether these values came from the glasses (else: axes only, no bias or scale). */
    val factory: Boolean = false,
) {
    /** Gyroscope (rad/s) of [s] in Monado's IMU frame, calibrated. */
    fun gyro(s: GlassesProtocol.ImuSample): FloatArray {
        val f = s.gyroMultiplier.toFloat() / s.gyroDivisor.toFloat()
        var v = FloatArray(3) { s.gyro[it].toFloat() * f }
        v = rotate(accelQGyro, v)
        for (i in 0..2) v[i] *= DEG_TO_RAD
        return finish(v, gyroBias, scaleGyro)
    }

    /** Accelerometer (m/s²) of [s] in Monado's IMU frame, calibrated. */
    fun accel(s: GlassesProtocol.ImuSample): FloatArray {
        val f = s.accelMultiplier.toFloat() / s.accelDivisor.toFloat()
        val v = FloatArray(3) { s.accel[it].toFloat() * f }
        for (i in 0..2) v[i] *= GRAVITY
        return finish(v, accelBias, scaleAccel)
    }

    private fun finish(v: FloatArray, bias: FloatArray, scale: FloatArray): FloatArray {
        // Pre-biased axes, bias and scale, post-biased axes.
        val p = floatArrayOf(-v[0], -v[2], -v[1])
        for (i in 0..2) p[i] = (p[i] - bias[i]) * scale[i]
        return floatArrayOf(p[0], -p[1], -p[2])
    }

    fun toJson() = JSONObject().apply {
        put("source", if (factory) "glasses factory calibration (IMU.device_1)" else "none received: axes only, no bias or scale")
        put("accel_bias", org.json.JSONArray(accelBias.map { it.toDouble() }))
        put("gyro_bias", org.json.JSONArray(gyroBias.map { it.toDouble() }))
        put("scale_accel", org.json.JSONArray(scaleAccel.map { it.toDouble() }))
        put("scale_gyro", org.json.JSONArray(scaleGyro.map { it.toDouble() }))
        put("accel_q_gyro_xyzw", org.json.JSONArray(accelQGyro.map { it.toDouble() }))
        put("arithmetic", "32-bit float, as Monado")
    }

    companion object {
        /** Monado's (float)MATH_GRAVITY_M_S2. */
        const val GRAVITY = 9.8066f
        /** Monado's (float)M_PI / 180.0f. */
        const val DEG_TO_RAD = Math.PI.toFloat() / 180f

        /** From the glasses' calibration JSON; axes only if it is missing or has no IMU part. */
        fun parse(json: String?): GlassesImuCalibration {
            val dev = runCatching { JSONObject(json!!).getJSONObject("IMU").getJSONObject("device_1") }.getOrNull()
                ?: return GlassesImuCalibration()
            fun vec(name: String, default: FloatArray, n: Int = 3): FloatArray {
                val a = dev.optJSONArray(name) ?: return default
                if (a.length() != n) return default
                return FloatArray(n) { a.getDouble(it).toFloat() }
            }
            return GlassesImuCalibration(
                accelBias = vec("accel_bias", FloatArray(3)),
                gyroBias = vec("gyro_bias", FloatArray(3)),
                scaleAccel = vec("scale_accel", floatArrayOf(1f, 1f, 1f)),
                scaleGyro = vec("scale_gyro", floatArrayOf(1f, 1f, 1f)),
                accelQGyro = vec("accel_q_gyro", floatArrayOf(0f, 0f, 0f, 1f), 4),
                factory = true,
            )
        }

        /** Rotates [v] by the unit quaternion [q] (x, y, z, w): q v q⁻¹ (as Eigen's q * v). */
        fun rotate(q: FloatArray, v: FloatArray): FloatArray {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            // t = 2 (q.xyz × v); v' = v + w t + q.xyz × t
            val tx = 2 * (y * v[2] - z * v[1])
            val ty = 2 * (z * v[0] - x * v[2])
            val tz = 2 * (x * v[1] - y * v[0])
            return floatArrayOf(
                v[0] + w * tx + (y * tz - z * ty),
                v[1] + w * ty + (z * tx - x * tz),
                v[2] + w * tz + (x * ty - y * tx),
            )
        }
    }
}
