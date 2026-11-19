package com.xreal.stereoview.oak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsPlanTest {
    @Test
    fun sameRateNeedsNoDecimation() {
        assertEquals(OakDevice.FpsPlan(30, 30, 30, 30, 1, 1, true), OakDevice.FpsPlan.of(30, 30))
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
    fun accelerometerRunsAtItsMaximumWithTheGyroscopes() {
        assertEquals(OakDevice.MAX_ACCEL_HZ, OakDevice.accelRateFor(OakDevice.MAX_GYRO_HZ))
        assertEquals(200, OakDevice.accelRateFor(200))
        assertEquals(0, OakDevice.accelRateFor(0))
    }
}
