package com.xreal.stereoview.render

import android.view.Surface
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.util.Mat3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StereoGeometryTest {
    private fun camera(sensorOrientation: Int) = CameraModel(
        label = "test", fx = 0.8, fy = 1.4, cx = 0.5, cy = 0.5,
        radial = DoubleArray(3), tangential = DoubleArray(2),
        sensorOrientation = sensorOrientation, factoryRotation = null, position = null,
        intrinsicsSource = "test",
    )

    /** Rotation by [rad] about the device's y axis (the long edge, in portrait). */
    private fun deviceRotY(rad: Double) = Mat3.fromQuaternion(0.0, sin(rad / 2), 0.0, cos(rad / 2))

    /**
     * In landscape (ROTATION_90) the device's y axis runs along the screen horizontal, so turning
     * about it is a pitch: a rotation about the rectified x axis. That must hold for every image
     * rotation, because it depends on how the device is held, not on how the camera is mounted.
     */
    @Test
    fun stabilizationAxesFollowTheScreenForAnyImageRotation() {
        val angle = Math.toRadians(5.0)
        for (override in listOf(-1, 0, 90, 180, 270)) {
            val cam = camera(sensorOrientation = 90)
            val geometry = StereoGeometry(listOf(cam, cam), useFactoryCalibration = true, Surface.ROTATION_90, override)
            val rect = geometry.toRectFrame(deviceRotY(angle))
            // A rotation about ±x leaves the x axis fixed.
            assertEquals("override $override", 1.0, kotlin.math.abs(rect[0]), 1e-9)
            assertEquals("override $override", 5.0, Mat3.angleDegrees(rect), 1e-6)
        }
    }
}
