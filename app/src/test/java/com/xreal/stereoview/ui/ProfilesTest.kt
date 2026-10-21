package com.xreal.stereoview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test
    fun jsonRoundTripKeepsEveryProfileSetting() {
        val custom = Profile(
            "Fast",
            StereoSettings().copy(
                convergencePx = 12f, depth = 1.5f, manualExposure = true, exposureTimeNs = 1_000_000, iso = 1600,
                targetFps = 60, maxResolution = 480, stabilization = Stabilization.HIGH, focusMode = FocusMode.INFINITY,
                recordView = RecordView.RECTIFIED, recordFormat = RecordFormat.JPEG, recordBitrateMbps = 100,
                imuRate = ImuRate.HZ_200, imuStreams = "GYROSCOPE",
            ),
        )
        val profiles = Profile.defaults + custom
        val parsed = Profile.fromJson(Profile.toJson(profiles))
        assertEquals(profiles.map { it.name }, parsed.map { it.name })
        for ((a, b) in profiles.zip(parsed)) {
            // Equal on every profile setting.
            assertFalse(a.name, a.differsFrom(StereoSettings().withProfile(b.settings)))
        }
    }

    @Test
    fun profilesLeaveDeviceSetupAlone() {
        val device = StereoSettings().copy(swapEyes = false, rotationOverride = 90, alignPitchDeg = 1.2f, cameraPair = "2+3")
        val euroc = Profile.defaults.first { it.name == Profile.EUROC }
        val applied = device.withProfile(euroc.settings)
        assertEquals(false, applied.swapEyes)
        assertEquals(90, applied.rotationOverride)
        assertEquals(1.2f, applied.alignPitchDeg)
        assertEquals("2+3", applied.cameraPair)
        assertEquals(Stabilization.OFF, applied.stabilization)
        assertFalse(euroc.differsFrom(applied))
        assertTrue(euroc.differsFrom(applied.copy(targetFps = 60)))
        // Device settings never count as a profile edit.
        assertFalse(euroc.differsFrom(applied.copy(showHud = true, glassesFovDeg = 57f)))
    }
}
