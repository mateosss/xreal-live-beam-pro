package com.xreal.stereoview.device

import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.record.OakRgbFormat
import com.xreal.stereoview.ui.StereoSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which setting changes restart a device's stream (and stop its recording), and which apply live. */
class RestartRulesTest {
    private val s = StereoSettings()

    @Test
    fun oakExposureChangesApplyLive() {
        val manual = s.copy(oakExposures = listOf(OakDevice.Exposure(false, 1000, 800, 0), OakDevice.Exposure(), OakDevice.Exposure(true, 4000, 400, 2)))
        assertFalse(OakSession.needsRestart(manual, s))
    }

    @Test
    fun oakPipelineChangesRestart() {
        for (changed in listOf(
            s.copy(oakFps = 15), s.copy(oakMonoHeight = 800), s.copy(oakRgbHeight = 0), s.copy(oakRgbFps = 5),
            s.copy(oakImuRateHz = 0), s.copy(oakUsb3 = false), s.copy(oakJpegQuality = 50),
            s.copy(oakRgbFormat = OakRgbFormat.PNG), s.copy(buffers = s.buffers.copy(oakLinkS = 4)),
        )) {
            assertTrue(changed.toString(), OakSession.needsRestart(changed, s))
        }
        // PNG and H.264 both take the raw frames (only JPEG is encoded on the camera): no restart between them.
        assertFalse(OakSession.needsRestart(s.copy(oakRgbFormat = OakRgbFormat.H264), s.copy(oakRgbFormat = OakRgbFormat.PNG)))
    }

    @Test
    fun recordingOnlySettingsNeverRestart() {
        val recordingOnly = s.copy(oakImageFormat = com.xreal.stereoview.record.GrayImageFormat.H264, grayJpegQuality = 50, oakVideoBitrateMbps = 30)
        assertFalse(OakSession.needsRestart(recordingOnly, s))
        assertFalse(GlassesSession.needsRestart(recordingOnly, s))
        assertFalse(PhoneSession.needsRestart(recordingOnly.copy(recordBitrateMbps = 80), s))
    }

    @Test
    fun glassesRestartOnlyForTheImu() {
        assertFalse(GlassesSession.needsRestart(s.copy(glassesManualExposure = true, glassesExposure100us = 10, glassesGain = 3), s))
        assertTrue(GlassesSession.needsRestart(s.copy(glassesImu = true), s))
        val withImu = s.copy(glassesImu = true)
        assertTrue(GlassesSession.needsRestart(withImu.copy(buffers = s.buffers.copy(glassesImuS = 9)), withImu))
        // Without the IMU its queue size doesn't matter.
        assertFalse(GlassesSession.needsRestart(s.copy(buffers = s.buffers.copy(glassesImuS = 9)), s))
    }

    @Test
    fun phoneRestartsForStreamShapeNotExposure() {
        assertFalse(PhoneSession.needsRestart(s.copy(manualExposure = true, iso = 1600, exposure = 2), s))
        assertTrue(PhoneSession.needsRestart(s.copy(maxResolution = 720), s))
        assertTrue(PhoneSession.needsRestart(s.copy(targetFps = 60), s))
        assertTrue(PhoneSession.needsRestart(s.copy(cameraPair = "2+3"), s))
    }
}
