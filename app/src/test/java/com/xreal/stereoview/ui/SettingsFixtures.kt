package com.xreal.stereoview.ui

import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.record.BitrateMode
import com.xreal.stereoview.record.GrayImageFormat
import com.xreal.stereoview.record.H264Profile
import com.xreal.stereoview.record.OakRgbFormat

/** Settings for the persistence tests. */
object SettingsFixtures {
    /** Every profile setting away from its default (as of 0.16; stored in profiles_v0.16.json). */
    val everyProfileSetting = StereoSettings().copy(
        convergencePx = 12.5f, depth = 1.5f, scaleMode = ScaleMode.STRETCH, exposure = -2, manualExposure = true,
        exposureTimeNs = 1_000_000, iso = 1600, targetFps = 60, maxResolution = 480, syncThresholdMs = 20,
        stabilization = Stabilization.HIGH, focusMode = FocusMode.INFINITY, recordView = RecordView.RECTIFIED,
        recordFormat = RecordFormat.JPEG, recordBitrateMbps = 100f, imuRate = ImuRate.HZ_200, imuStreams = "GYROSCOPE",
        cameraSource = CameraSource.OAK, glassesImu = true, glassesRectify = false, glassesManualExposure = true,
        glassesExposure100us = 77, glassesGain = 5, oakMonoHeight = 800, oakFps = 15, oakRgbHeight = 720,
        oakJpegQuality = 80, oakImuRateHz = 200, oakRgbFps = 5f, previewStyle = PreviewStyle.PASSTHROUGH, oakUsb3 = false,
        oakRgbFormat = OakRgbFormat.H264, oakRgbVideoBitrateMbps = 33f,
        oakExposures = listOf(OakDevice.Exposure(false, 1234, 800, 0), OakDevice.Exposure(true, 4000, 400, 3), OakDevice.Exposure(false, 20, 100, -1)),
        glassesImageFormat = GrayImageFormat.JPEG, oakImageFormat = GrayImageFormat.H264, grayJpegQuality = 70,
        videoBitrateMode = BitrateMode.VBR, videoKeyframeIntervalS = 2.5f, videoProfile = H264Profile.BASELINE,
        videoSoftwareEncoder = true, glassesVideoBitrateMbps = 6f, oakVideoBitrateMbps = 18f,
    )
}
