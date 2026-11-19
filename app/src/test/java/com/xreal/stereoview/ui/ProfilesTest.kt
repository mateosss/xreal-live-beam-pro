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
        val device = StereoSettings().copy(swapEyes = false, rotationOverride = 90, beamProAlignPitchDeg = 1.2f, cameraPair = "2+3")
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

class ProfileMigrationTest {
    @Test
    fun untouchedGlassesProfilesMoveToFillButEditedOnesStay() {
        val euroc = Profile.defaults.first { it.name == Profile.GLASSES_EUROC }
        // As stored by 0.12: Fit, and the phone IMU streams it recorded then.
        val old = euroc.settings.copy(scaleMode = ScaleMode.FIT, imuRate = ImuRate.DIRECT_VERY_FAST, imuStreams = FOUR)
        val untouched = euroc.copy(settings = old)
        val edited = euroc.copy(settings = old.copy(convergencePx = 10f))
        assertEquals(ScaleMode.FILL, Profile.migrate(listOf(untouched))[0].settings.scaleMode)
        assertEquals(ScaleMode.FIT, Profile.migrate(listOf(edited))[0].settings.scaleMode)
    }

    @Test
    fun untouchedOakProfilesMoveTo400pAndMaximumImu() {
        val euroc = Profile.defaults.first { it.name == Profile.OAK_EUROC }
        val old = euroc.settings.copy(oakMonoHeight = 800, oakImuRateHz = 200, imuRate = ImuRate.DIRECT_VERY_FAST, imuStreams = FOUR)
        val migrated = Profile.migrate(listOf(euroc.copy(settings = old)))[0].settings
        assertEquals(400, migrated.oakMonoHeight)
        assertEquals(com.xreal.stereoview.oak.OakDevice.MAX_GYRO_HZ, migrated.oakImuRateHz)
        val edited = Profile.migrate(listOf(euroc.copy(settings = old.copy(oakFps = 15))))[0].settings
        assertEquals(800, edited.oakMonoHeight)

        val pass = Profile.defaults.first { it.name == Profile.OAK_PASSTHROUGH }
        val oldPass = pass.copy(settings = pass.settings.copy(oakMonoHeight = 720, previewStyle = PreviewStyle.CAPTURE))
        assertEquals(400, Profile.migrate(listOf(oldPass))[0].settings.oakMonoHeight)
    }

    @Test
    fun passthroughProfilesShowPassthroughStyleAndDatasetOnesCapture() {
        for (p in Profile.defaults) {
            val expected = if (p.name.contains("EuRoC")) PreviewStyle.CAPTURE else PreviewStyle.PASSTHROUGH
            assertEquals(p.name, expected, p.settings.previewStyle)
        }
        // As stored by 0.15.0 (capture style everywhere): untouched ones move, edited ones stay.
        for (name in listOf(Profile.PASSTHROUGH, Profile.OAK_PASSTHROUGH, Profile.GLASSES_PASSTHROUGH)) {
            val p = Profile.defaults.first { it.name == name }
            val old = p.settings.copy(previewStyle = PreviewStyle.CAPTURE)
            assertEquals(name, PreviewStyle.PASSTHROUGH, Profile.migrate(listOf(p.copy(settings = old)))[0].settings.previewStyle)
            val edited = old.copy(convergencePx = 7f)
            assertEquals(name, PreviewStyle.CAPTURE, Profile.migrate(listOf(p.copy(settings = edited)))[0].settings.previewStyle)
        }
    }

    private companion object {
        const val FOUR = "ACCELEROMETER,GYROSCOPE,ACCELEROMETER_UNCALIBRATED,GYROSCOPE_UNCALIBRATED"
    }
}
