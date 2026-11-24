package com.xreal.stereoview.glasses

import com.xreal.stereoview.util.Mat3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A tracking camera of the glasses: Kannala-Brandt (KB4 / "equidistant") intrinsics in pixels
 * of the upright 480×640 image, and its pose in the IMU frame ([imuFromCamera], [positionInImu]).
 * Camera frame: x right, y down, z forward in the image.
 */
class Kb4Camera(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    /** k1..k4 of θd = θ(1 + k1θ² + k2θ⁴ + k3θ⁶ + k4θ⁸). */
    val k: DoubleArray,
    /** Rotation taking camera-frame vectors to the IMU frame. */
    val imuFromCamera: DoubleArray,
    /** Optical center in the IMU frame, meters. */
    val positionInImu: DoubleArray,
) {
    /** Pixel (x, y) of a camera-frame ray, or null behind the camera. */
    fun project(ray: DoubleArray): DoubleArray? {
        if (ray[2] <= 0 && sqrt(ray[0] * ray[0] + ray[1] * ray[1]) < 1e-12) return null
        val r = sqrt(ray[0] * ray[0] + ray[1] * ray[1])
        val theta = atan2(r, ray[2])
        val d = distort(theta)
        val s = if (r > 1e-12) d / r else 0.0
        return doubleArrayOf(fx * ray[0] * s + cx, fy * ray[1] * s + cy)
    }

    /** Unit camera-frame ray through pixel (x, y). */
    fun unproject(x: Double, y: Double): DoubleArray {
        val mx = (x - cx) / fx
        val my = (y - cy) / fy
        val rd = sqrt(mx * mx + my * my)
        if (rd < 1e-12) return doubleArrayOf(0.0, 0.0, 1.0)
        // Newton on θ(1 + k1θ² + …) = rd.
        var theta = rd
        repeat(20) {
            val t2 = theta * theta
            val f = distort(theta) - rd
            val df = 1 + t2 * (3 * k[0] + t2 * (5 * k[1] + t2 * (7 * k[2] + t2 * 9 * k[3])))
            val step = f / df
            theta -= step
            if (abs(step) < 1e-12) return@repeat
        }
        val s = sin(theta) / rd
        return doubleArrayOf(mx * s, my * s, cos(theta))
    }

    private fun distort(theta: Double): Double {
        val t2 = theta * theta
        return theta * (1 + t2 * (k[0] + t2 * (k[1] + t2 * (k[2] + t2 * k[3]))))
    }

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 640

        /**
         * Monado's hardcoded Basalt calibration of one Air 2 Ultra (xreal_air tracker.c, BSL-1.0):
         * close enough for a rectified preview of any unit, but calibrate yours for SLAM.
         */
        val REFERENCE: List<Kb4Camera> = listOf(
            Kb4Camera(
                240.57122490048506, 240.74854215383074, 238.9491196368791, 320.29607073555209,
                doubleArrayOf(0.020856425660087508, 0.0299260962615731, -0.032168347423742738, 0.007262692844289708),
                Mat3.fromQuaternion(0.9973474875662636, 0.0018642996931548873, -0.044823515329715259, 0.057318111495431169),
                doubleArrayOf(-0.04919995913471168, -0.001209836612281824, -0.009887014172375066),
            ),
            Kb4Camera(
                241.69265557166814, 241.87529140953999, 239.12284466054269, 319.1242983084969,
                doubleArrayOf(0.019200339780075235, 0.03074245961432518, -0.03197361414068182, 0.006952063686239072),
                Mat3.fromQuaternion(0.9972690586706283, -0.0024372791609311458, 0.04218557174298931, 0.06057113029494838),
                doubleArrayOf(0.0878646673959822, -0.002347085430219617, -0.00992990350848626),
            ),
        )
    }
}
