package com.xreal.stereoview.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesImuCalibrationTest {
    /** A sample with gyroscope [degPerS] (°/s) and accelerometer [g] (g), at 1e-4 resolution. */
    private fun sample(degPerS: DoubleArray, g: DoubleArray) = GlassesProtocol.ImuSample(
        timestampNs = 0, temperatureRaw = 0,
        gyro = IntArray(3) { Math.round(degPerS[it] * 1e4).toInt() }, gyroMultiplier = 1, gyroDivisor = 10_000,
        accel = IntArray(3) { Math.round(g[it] * 1e4).toInt() }, accelMultiplier = 1, accelDivisor = 10_000,
        mag = IntArray(3), magMultiplier = 1, magDivisor = 1,
    )

    @Test
    fun axesOnlyTurnTheAppsStillSampleIntoMonados() {
        // A still sample as this app recorded it before (sensor axes, m/s²) and one Monado
        // recorded in about the same pose: (0.25..0.41, 7.0, −6.9).
        val before = doubleArrayOf(-0.4345, -6.7301, 7.4531)
        val s = sample(DoubleArray(3), DoubleArray(3) { before[it] / GlassesImuCalibration.GRAVITY })
        val a = GlassesImuCalibration().accel(s)
        assertArrayEquals(doubleArrayOf(0.4345, 7.4531, -6.7301), a, 1e-3) // (−x, z, y)
        assertEquals(0.33, a[0], 0.2)
        assertEquals(7.04, a[1], 0.5)
        assertEquals(-6.88, a[2], 0.3)
    }

    @Test
    fun factoryCalibrationIsAppliedLikeMonado() {
        val json = """{"IMU":{"device_1":{"accel_bias":[0.1,0.2,0.3],"gyro_bias":[0.01,0.02,0.03],
            "scale_accel":[1.1,1.0,0.9],"scale_gyro":[2.0,1.0,1.0],"accel_q_gyro":[0,0,0.7071067811865476,0.7071067811865476]}}}"""
        val cal = GlassesImuCalibration.parse(json)
        val s = sample(doubleArrayOf(10.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        // Accelerometer: (0, 0, g) → pre (0, −g, 0) → minus bias, times scale → post (x, −y, −z).
        val g = GlassesImuCalibration.GRAVITY
        assertArrayEquals(doubleArrayOf(-0.1 * 1.1, (g + 0.2) * 1.0, 0.3 * 0.9), cal.accel(s), 1e-9)
        // Gyroscope: 10 °/s on x, turned 90° about z → y; rad/s; pre (0, 0, −w); bias, scale; post.
        val w = Math.toRadians(10.0)
        assertArrayEquals(doubleArrayOf(-0.01 * 2.0, 0.02, -(-w - 0.03)), cal.gyro(s), 1e-9)
    }

    @Test
    fun missingCalibrationMeansAxesOnly() {
        val cal = GlassesImuCalibration.parse("{}")
        assertEquals(false, cal.factory)
        val s = sample(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.0, 0.0, 0.0))
        val r = Math.toRadians(1.0)
        assertArrayEquals(doubleArrayOf(-r, 3 * r, 2 * r), cal.gyro(s), 1e-9)
    }
}
