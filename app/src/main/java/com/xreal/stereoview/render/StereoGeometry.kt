package com.xreal.stereoview.render

import android.view.Surface
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.util.Mat3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rectifies a camera pair into one shared "display" frame: x along the baseline (screen right),
 * y down, z forward. Both eyes then look along parallel axes with no vertical disparity, and a
 * point at infinity lands on the same spot in both views.
 *
 * Each eye is drawn by mapping every output pixel (u, v) to a ray in that frame, optionally
 * stabilizing and aligning it, and rotating it into the eye's camera, where the shader projects
 * it through the camera's intrinsics and distortion.
 */
class StereoGeometry(
    private val cameras: List<CameraModel>,
    useFactoryCalibration: Boolean,
    displayRotation: Int,
    rotationOverride: Int,
) {
    /**
     * Device → camera rotations. A rotation override states how the image sits on screen, which
     * pins down how the camera is mounted relative to the device (and so the gyro) as well.
     */
    private val orientations = cameras.map { camera ->
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = if (rotationOverride >= 0) (rotationOverride + displayDegrees) % 360 else camera.sensorOrientation
        camera.deviceToCamera(sensorOrientation, useFactoryCalibration)
    }
    /** Device → camera rotation of each camera (x right, y down, z forward in its raw image). */
    val deviceToCamera = orientations.map { it.first }

    /** Rectified frame ← left camera frame. */
    private val rectFromLeft: DoubleArray

    /** Camera i frame ← rectified frame. */
    private val cameraFromRect: List<DoubleArray>

    /** Rectified frame ← device frame. */
    private val rectFromDevice: DoubleArray

    /** Whether camera image x runs along the display's horizontal axis (else the image is turned 90°). */
    val imageXIsHorizontal: Boolean

    /** Right lens position minus left lens position in the rectified frame, meters, if known. */
    val baselineInRect: DoubleArray?

    val summary: String

    init {
        val left = deviceToCamera[0]
        val right = deviceToCamera[1]
        val leftFromRight = Mat3.mul(left, Mat3.transpose(right))

        // Screen right expressed in the left camera's frame.
        val screenRight = Mat3.mulVec(left, screenRightInDevice(displayRotation))

        // Baseline direction from the factory lens positions, if usable. It only refines the
        // horizontal axis; when it disagrees with the screen horizontal, the positions are wrong.
        val pl = cameras[0].position
        val pr = cameras[1].position
        var baselineNote = ", baseline direction assumed horizontal"
        var baseline: DoubleArray? = null
        if (useFactoryCalibration && pl != null && pr != null && cameras[0] !== cameras[1]) {
            val b = Mat3.mulVec(left, doubleArrayOf(pr[0] - pl[0], pr[1] - pl[1], pr[2] - pl[2]))
            val mm = Mat3.norm(b) * 1000
            val angle = Math.toDegrees(acos(abs(Mat3.dot(Mat3.normalize(b), screenRight)).coerceAtMost(1.0)))
            if (mm in 10.0..250.0 && angle < 30) {
                baseline = Mat3.normalize(b)
                baselineNote = ", baseline %.1f mm from lens poses (%.1f° from horizontal)".format(mm, angle)
            } else {
                baselineNote = ", lens poses ignored (%.1f mm, %.0f° from horizontal)".format(mm, angle)
            }
        }

        // Shared forward axis: halfway between both cameras' optical axes.
        val forward = Mat3.normalize(doubleArrayOf(0.0, 0.0, 1.0).let { z ->
            val zr = Mat3.mulVec(leftFromRight, z)
            doubleArrayOf(z[0] + zr[0], z[1] + zr[1], z[2] + zr[2])
        })
        var e1 = baseline ?: screenRight
        if (Mat3.dot(e1, screenRight) < 0) e1 = doubleArrayOf(-e1[0], -e1[1], -e1[2])
        e1 = Mat3.normalize(e1.let { v ->
            val d = Mat3.dot(v, forward)
            doubleArrayOf(v[0] - d * forward[0], v[1] - d * forward[1], v[2] - d * forward[2])
        })
        val e2 = Mat3.normalize(Mat3.cross(forward, e1))
        val e3 = Mat3.cross(e1, e2)
        rectFromLeft = Mat3.fromRows(e1, e2, e3)

        val leftFromRect = Mat3.transpose(rectFromLeft)
        cameraFromRect = listOf(leftFromRect, Mat3.mul(right, Mat3.transpose(left), leftFromRect))
        rectFromDevice = Mat3.mul(rectFromLeft, left)
        imageXIsHorizontal = abs(e1[0]) >= abs(e1[1])
        baselineInRect = if (pl != null && pr != null && cameras[0] !== cameras[1]) {
            Mat3.mulVec(rectFromDevice, doubleArrayOf(pr[0] - pl[0], pr[1] - pl[1], pr[2] - pl[2]))
        } else {
            null
        }

        summary = buildString {
            append("L: ${orientations[0].second}; R: ${orientations[1].second}")
            append("; relative rotation %.2f°".format(Mat3.angleDegrees(leftFromRight)))
            append(if (cameras[0] === cameras[1]) ", single camera" else baselineNote)
        }
    }

    /** Converts a device-frame rotation (from [Stabilizer]) into the rectified frame. */
    fun toRectFrame(deviceRotation: DoubleArray): DoubleArray =
        Mat3.mul(rectFromDevice, deviceRotation, Mat3.transpose(rectFromDevice))

    /** Half-FOV tangents (horizontal, vertical) each eye shows, for an eye of physical aspect [eyeAspect]. */
    fun displayTangents(mode: ScaleMode, eyeAspect: Double, glassesFovDeg: Double): Pair<Double, Double> {
        val camH = cameras.minOf { if (imageXIsHorizontal) it.tanHalfFovX else it.tanHalfFovY }
        val camV = cameras.minOf { if (imageXIsHorizontal) it.tanHalfFovY else it.tanHalfFovX }
        return when (mode) {
            ScaleMode.REAL -> {
                val diagonal = tan(Math.toRadians(glassesFovDeg / 2))
                val norm = sqrt(1 + eyeAspect * eyeAspect)
                diagonal * eyeAspect / norm to diagonal / norm
            }
            ScaleMode.FILL -> min(camV, camH / eyeAspect).let { it * eyeAspect to it }
            ScaleMode.FIT -> max(camV, camH / eyeAspect).let { it * eyeAspect to it }
            ScaleMode.STRETCH -> camH to camV
        }
    }

    /**
     * Maps output coordinates (u, v, 1) of one eye (u right, v up, 0..1) to a ray in camera
     * [camera]'s frame.
     *
     * @param shift horizontal image offset in eye widths (positive samples further right)
     * @param pitchDeg vertical alignment correction, split half per eye in opposite directions
     * @param rollDeg rotational alignment correction, split the same way
     * @param stabilization rotation in the rectified frame, see [toRectFrame]
     */
    fun rayMatrix(
        camera: Int,
        tanH: Double,
        tanV: Double,
        shift: Double,
        zoom: Double,
        pitchDeg: Double,
        rollDeg: Double,
        stabilization: DoubleArray,
    ): DoubleArray {
        val sx = 2 * tanH / zoom
        val sy = 2 * tanV / zoom
        val fromOutput = doubleArrayOf(
            sx, 0.0, (shift - 0.5) * sx,
            0.0, -sy, sy / 2,
            0.0, 0.0, 1.0,
        )
        return Mat3.mul(cameraFromVirtual(camera, pitchDeg, rollDeg), stabilization, fromOutput)
    }

    /**
     * Rotation from the eye's virtual (rectified, aligned) camera to the real camera [camera],
     * both with x right, y down, z forward: a ray in the rectified image maps to this times it.
     */
    fun cameraFromVirtual(camera: Int, pitchDeg: Double, rollDeg: Double): DoubleArray {
        val sign = if (camera == 0) -0.5 else 0.5
        val align = Mat3.mul(Mat3.rotX(Math.toRadians(sign * pitchDeg)), Mat3.rotZ(Math.toRadians(sign * rollDeg)))
        return Mat3.mul(cameraFromRect[camera], align)
    }

    /**
     * Pinhole intrinsics (fx, fy, cx, cy) in pixels, row 0 at the top, of an eye image of
     * [width]×[height] drawn by [rayMatrix] with the same tangents, shift and zoom.
     */
    fun rectifiedIntrinsics(width: Int, height: Int, tanH: Double, tanV: Double, shift: Double, zoom: Double): DoubleArray {
        val sx = 2 * tanH / zoom
        val sy = 2 * tanV / zoom
        return doubleArrayOf(width / sx, height / sy, width * (0.5 - shift), height / 2.0)
    }

    private fun screenRightInDevice(rotation: Int): DoubleArray = when (rotation) {
        Surface.ROTATION_90 -> doubleArrayOf(0.0, -1.0, 0.0)
        Surface.ROTATION_180 -> doubleArrayOf(-1.0, 0.0, 0.0)
        Surface.ROTATION_270 -> doubleArrayOf(0.0, 1.0, 0.0)
        else -> doubleArrayOf(1.0, 0.0, 0.0)
    }
}
