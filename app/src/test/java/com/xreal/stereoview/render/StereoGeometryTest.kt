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

    /** The exported pinhole intrinsics must reproduce where [StereoGeometry.rayMatrix] samples each pixel. */
    @Test
    fun rectifiedIntrinsicsMatchTheRayMapping() {
        val cam = camera(sensorOrientation = 90)
        val geometry = StereoGeometry(listOf(cam, cam), useFactoryCalibration = true, Surface.ROTATION_90, 270)
        val (w, h) = 1280 to 720
        val (tanH, tanV, shift, zoom) = listOf(0.6, 0.35, 0.04, 1.3)
        val k = geometry.rectifiedIntrinsics(w, h, tanH, tanV, shift, zoom)
        val virtualFromCamera = Mat3.transpose(geometry.cameraFromVirtual(0, 0.0, 0.0))
        for ((u, v) in listOf(0.1 to 0.2, 0.5 to 0.5, 0.9 to 0.75)) {
            val ray = geometry.rayMatrix(0, tanH, tanV, shift, zoom, 0.0, 0.0, Mat3.identity())
            val virtual = Mat3.mulVec(virtualFromCamera, Mat3.mulVec(ray, doubleArrayOf(u, v, 1.0)))
            // Pixel of output (u, v): column u·w, row (1 − v)·h from the top.
            assertEquals(u * w, k[0] * virtual[0] / virtual[2] + k[2], 1e-6)
            assertEquals((1 - v) * h, k[1] * virtual[1] / virtual[2] + k[3], 1e-6)
        }
    }

    @Test
    fun quaternionRoundTrips() {
        val m = Mat3.mul(Mat3.rotX(0.3), Mat3.rotZ(-1.1))
        val q = Mat3.toQuaternion(m)
        val back = Mat3.fromQuaternion(q[1], q[2], q[3], q[0])
        for (i in 0 until 9) assertEquals(m[i], back[i], 1e-9)
    }
}
