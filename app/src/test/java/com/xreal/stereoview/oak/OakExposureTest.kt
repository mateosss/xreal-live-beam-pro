package com.xreal.stereoview.oak

import com.xreal.stereoview.ui.Profile
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.ui.withProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OakExposureTest {
    @Test
    fun perCameraExposuresRoundTripAndClamp() {
        val list = listOf(OakDevice.Exposure(false, 1000, 800, 0), OakDevice.Exposure(), OakDevice.Exposure(true, 4000, 400, 3))
        assertEquals(list, OakDevice.Exposure.listFromJson(OakDevice.Exposure.listToJson(list)))
        assertNull(OakDevice.Exposure.listFromJson(null))
        assertNull(OakDevice.Exposure.listFromJson("garbage"))
        assertEquals(OakDevice.Exposure(false, 33_000, 1600, -9), OakDevice.Exposure(false, 99_000, 5000, -20).clamped())
        assertEquals("exposure cam0 1000 µs ISO 800, cam1 auto, cam2 auto +3", OakDevice.Exposure.summary(list, true))
        assertEquals("auto exposure", OakDevice.Exposure.summary(List(3) { OakDevice.Exposure() }, true))
    }

    @Test
    fun profilesKeepPerCameraExposuresAndOldOnesTakeTheSharedSettings() {
        val e = listOf(OakDevice.Exposure(false, 2000, 200, 0), OakDevice.Exposure(false, 3000, 300, 0), OakDevice.Exposure())
        val p = Profile("x", StereoSettings().copy(oakExposures = e, oakRgbFormat = com.xreal.stereoview.record.OakRgbFormat.H264, oakRgbVideoBitrateMbps = 40))
        val back = Profile.fromJson(Profile.toJson(listOf(p)))[0].settings
        assertEquals(e, back.oakExposures)
        assertEquals(StereoSettings().withProfile(p.settings), StereoSettings().withProfile(back))
        // A profile stored before 0.16: manual exposure in the shared settings applies to every OAK camera.
        val old = """[{"name":"o","settings":{"manualExposure":true,"exposureTimeNs":5000000,"iso":800}}]"""
        assertEquals(List(3) { OakDevice.Exposure(false, 5000, 800, 0) }, Profile.fromJson(old)[0].settings.oakExposures)
    }
}
