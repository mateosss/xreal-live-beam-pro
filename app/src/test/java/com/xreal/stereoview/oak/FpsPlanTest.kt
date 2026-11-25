package com.xreal.stereoview.oak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsPlanTest {
    @Test
    fun sameRateNeedsNoDecimation() {
        assertEquals(OakDevice.FpsPlan(30, 30f, 30, 30, 1, 1, true), OakDevice.FpsPlan.of(30, 30))
    }

    @Test
    fun slowerColorKeepsEveryNthFrameAtTheMonoRate() {
        val p = OakDevice.FpsPlan.of(30, 10)
        assertTrue(p.synchronized)
        assertEquals(30, p.monoSensorFps)
        assertEquals(30, p.rgbSensorFps)
        assertEquals(1, p.monoEvery)
        assertEquals(3, p.rgbEvery)
    }

    @Test
    fun fasterColorRunsAllSensorsFasterAndThinsTheMonoPair() {
        val p = OakDevice.FpsPlan.of(15, 30)
        assertTrue(p.synchronized)
        assertEquals(30, p.monoSensorFps)
        assertEquals(2, p.monoEvery)
        assertEquals(1, p.rgbEvery)
    }

    @Test
    fun ratesThatAreNotMultiplesRunFreely() {
        val p = OakDevice.FpsPlan.of(30, 20)
        assertFalse(p.synchronized)
        assertEquals(30, p.monoSensorFps)
        assertEquals(20, p.rgbSensorFps)
        assertEquals(1, p.monoEvery)
        assertEquals(1, p.rgbEvery)
    }

    @Test
    fun colorBelowOneFpsKeepsOneInNMonoFrames() {
        val one = OakDevice.FpsPlan.of(30, 1f)
        assertTrue(one.synchronized)
        assertEquals(30, one.rgbSensorFps)
        assertEquals(30, one.rgbEvery)
        assertEquals(1f, one.rgbFps)
        val tenth = OakDevice.FpsPlan.of(30, 0.1f)
        assertEquals(300, tenth.rgbEvery)
        assertEquals(30, tenth.rgbSensorFps)
        assertEquals(1, tenth.monoEvery)
        assertEquals(0.5f, OakDevice.FpsPlan.of(20, 0.5f).rgbFps, 1e-6f)
    }

    @Test
    fun fractionalColorRatesRoundToTheNearestKeptFrame() {
        // 45 fps / 0.2 = 225 frames; 45 / 7 isn't whole, so 0.7 fps keeps 1 in 64 (0.703 fps).
        assertEquals(225, OakDevice.FpsPlan.of(45, 0.2f).rgbEvery)
        val p = OakDevice.FpsPlan.of(45, 0.7f)
        assertTrue(p.synchronized)
        assertEquals(64, p.rgbEvery)
        assertEquals(45f / 64, p.rgbFps, 1e-6f)
    }

    @Test
    fun zeroColorRateMeansTheMonoRate() {
        assertEquals(OakDevice.FpsPlan.of(30, 30), OakDevice.FpsPlan.of(30, 0f))
    }

    @Test
    fun accelerometerRunsAtItsMaximumWithTheGyroscopes() {
        assertEquals(OakDevice.MAX_ACCEL_HZ, OakDevice.accelRateFor(OakDevice.MAX_GYRO_HZ))
        assertEquals(200, OakDevice.accelRateFor(200))
        assertEquals(0, OakDevice.accelRateFor(0))
    }
}
