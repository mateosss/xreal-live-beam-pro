package com.xreal.stereoview.render

import com.xreal.stereoview.glasses.Kb4Camera
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.util.Mat3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Stereo rectification of the glasses' two fisheye tracking cameras, as [StereoGeometry] does
 * for the Beam Pro's: a shared frame with x along the baseline, y down and z halfway between
 * the optical axes, so both eyes look along parallel axes and distant points coincide.
 */
class GlassesGeometry(val cameras: List<Kb4Camera> = Kb4Camera.REFERENCE) {
    /** Camera i frame ← rectified frame. */
    private val cameraFromRect: List<DoubleArray>

    /** Right camera position minus left in the rectified frame, meters. */
    val baselineInRect: DoubleArray

    /** Half-FOV tangents (horizontal, vertical) that stay inside both images. */
    private val tanH: Double
    private val tanV: Double

    init {
        val left = Mat3.transpose(cameras[0].imuFromCamera) // IMU → camera
        val right = Mat3.transpose(cameras[1].imuFromCamera)
        val leftFromRight = Mat3.mul(left, cameras[1].imuFromCamera)
        val p0 = cameras[0].positionInImu
        val p1 = cameras[1].positionInImu
        val baseline = Mat3.mulVec(left, doubleArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]))

        val zr = Mat3.mulVec(leftFromRight, doubleArrayOf(0.0, 0.0, 1.0))
        val forward = Mat3.normalize(doubleArrayOf(zr[0], zr[1], 1 + zr[2]))
        var e1 = Mat3.normalize(baseline)
        if (e1[0] < 0) e1 = doubleArrayOf(-e1[0], -e1[1], -e1[2]) // keep image right as screen right
        val d = Mat3.dot(e1, forward)
        e1 = Mat3.normalize(doubleArrayOf(e1[0] - d * forward[0], e1[1] - d * forward[1], e1[2] - d * forward[2]))
        val e2 = Mat3.normalize(Mat3.cross(forward, e1))
        val e3 = Mat3.cross(e1, e2)
        val rectFromLeft = Mat3.fromRows(e1, e2, e3)
        val leftFromRect = Mat3.transpose(rectFromLeft)
        cameraFromRect = listOf(leftFromRect, Mat3.mul(right, cameras[0].imuFromCamera, leftFromRect))
        baselineInRect = Mat3.mulVec(rectFromLeft, baseline)

        // Inscribed view: the rays through each image's edge midpoints, in the rectified frame.
        var h = Double.MAX_VALUE
        var v = Double.MAX_VALUE
        cameras.forEachIndexed { i, c ->
            val rectFromCamera = Mat3.transpose(cameraFromRect[i])
            fun tangent(x: Double, y: Double, axis: Int): Double {
                val ray = Mat3.mulVec(rectFromCamera, c.unproject(x, y))
                return if (ray[2] > 1e-6) abs(ray[axis] / ray[2]) else MAX_TAN
            }
            h = min(h, min(tangent(0.0, c.cy, 0), tangent(Kb4Camera.WIDTH.toDouble(), c.cy, 0)))
            v = min(v, min(tangent(c.cx, 0.0, 1), tangent(c.cx, Kb4Camera.HEIGHT.toDouble(), 1)))
        }
        tanH = min(h, MAX_TAN)
        tanV = min(v, MAX_TAN)
    }

    val summary: String
        get() = "glasses reference calibration (Monado), baseline %.1f mm, inscribed view %.0f°×%.0f°".format(
            Mat3.norm(baselineInRect) * 1000,
            Math.toDegrees(2 * kotlin.math.atan(tanH)), Math.toDegrees(2 * kotlin.math.atan(tanV)),
        )

    /** Half-FOV tangents each eye shows, for an eye of physical aspect [eyeAspect]; see [StereoGeometry.displayTangents]. */
    fun displayTangents(mode: ScaleMode, eyeAspect: Double, glassesFovDeg: Double): Pair<Double, Double> = when (mode) {
        ScaleMode.REAL -> {
            val diagonal = tan(Math.toRadians(glassesFovDeg / 2))
            val norm = sqrt(1 + eyeAspect * eyeAspect)
            diagonal * eyeAspect / norm to diagonal / norm
        }
        ScaleMode.FILL -> min(tanV, tanH / eyeAspect).let { it * eyeAspect to it }
        ScaleMode.FIT -> max(tanV, tanH / eyeAspect).let { it * eyeAspect to it }
        ScaleMode.STRETCH -> tanH to tanV
    }

    /** Output (u, v, 1) of one eye → ray in camera [camera]'s frame; see [StereoGeometry.rayMatrix]. */
    fun rayMatrix(camera: Int, tanH: Double, tanV: Double, shift: Double, zoom: Double, pitchDeg: Double, rollDeg: Double): DoubleArray =
        Mat3.mul(cameraFromVirtual(camera, pitchDeg, rollDeg), StereoGeometry.outputToRay(tanH, tanV, shift, zoom))

    fun cameraFromVirtual(camera: Int, pitchDeg: Double, rollDeg: Double): DoubleArray =
        Mat3.mul(cameraFromRect[camera], StereoGeometry.alignment(camera, pitchDeg, rollDeg))

    private companion object {
        /** Wider rectified views are mostly stretched edge pixels. */
        val MAX_TAN = tan(Math.toRadians(60.0))
    }
}
