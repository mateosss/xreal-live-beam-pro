package com.xreal.stereoview.render

import com.xreal.stereoview.glasses.Kb4Camera
import com.xreal.stereoview.ui.ScaleMode
import com.xreal.stereoview.util.Mat3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlassesGeometryTest {
    @Test
    fun kb4UnprojectInvertsProject() {
        val c = Kb4Camera.REFERENCE[0]
        for ((x, y) in listOf(240.0 to 320.0, 10.0 to 20.0, 470.0 to 630.0, 100.0 to 500.0)) {
            val p = c.project(c.unproject(x, y))!!
            assertEquals(x, p[0], 1e-6)
            assertEquals(y, p[1], 1e-6)
        }
    }

    /** A distant point lands on the same output spot in both rectified eyes, with no vertical disparity. */
    @Test
    fun distantPointsCoincideInBothEyes() {
        val g = GlassesGeometry()
        val (tanH, tanV) = g.displayTangents(ScaleMode.FIT, 0.75, 52.0)
        for ((u, v) in listOf(0.5 to 0.5, 0.2 to 0.7, 0.8 to 0.3)) {
            // Ray of output (u, v) in camera 0, then the same direction seen from camera 1.
            val ray0 = Mat3.mulVec(g.rayMatrix(0, tanH, tanV, 0.0, 1.0, 0.0, 0.0), doubleArrayOf(u, v, 1.0))
            val imu = Mat3.mulVec(g.cameras[0].imuFromCamera, ray0)
            val ray1 = Mat3.mulVec(Mat3.transpose(g.cameras[1].imuFromCamera), imu)
            val toOutput1 = invert(g.rayMatrix(1, tanH, tanV, 0.0, 1.0, 0.0, 0.0))
            val o = Mat3.mulVec(toOutput1, ray1)
            assertEquals(u, o[0] / o[2], 1e-9)
            assertEquals(v, o[1] / o[2], 1e-9)
        }
    }

    /** The baseline has no vertical component in the rectified frame (it may have a forward one). */
    @Test
    fun baselineIsHorizontalAndAboutFourteenCentimeters() {
        val b = GlassesGeometry().baselineInRect
        assertTrue("x ${b[0]}", abs(b[0] - 0.137) < 0.005)
        assertTrue("y ${b[1]}", abs(b[1]) < 1e-9)
        assertTrue("z ${b[2]}", abs(b[2]) < 0.005)
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val (a, b, c, d, e, f, g, h, i) = m.toList()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]
    private operator fun <T> List<T>.component8() = this[7]
    private operator fun <T> List<T>.component9() = this[8]
}
