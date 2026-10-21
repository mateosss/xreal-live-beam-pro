package com.xreal.stereoview.render

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class AlignmentEstimatorTest {
    private val width = 320
    private val height = 180

    /** A smooth random scene, sampled at continuous coordinates so it can be shifted and rotated. */
    private class Scene(seed: Int) {
        private val random = Random(seed)
        private val blobs = List(60) {
            doubleArrayOf(random.nextDouble(-50.0, 370.0), random.nextDouble(-50.0, 230.0), random.nextDouble(4.0, 20.0), random.nextDouble(-1.0, 1.0))
        }

        fun at(x: Double, y: Double): Float = blobs.sumOf { (bx, by, r, a) ->
            val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
            a * kotlin.math.exp(-d2 / (r * r))
        }.toFloat()
    }

    private fun render(scene: Scene, transform: (Double, Double) -> Pair<Double, Double>) = FloatArray(width * height) { i ->
        val (x, y) = transform((i % width).toDouble(), (i / width).toDouble())
        scene.at(x, y)
    }

    @Test
    fun rightViewLowerGivesPositiveOffset() {
        val scene = Scene(1)
        val left = render(scene) { x, y -> x to y }
        val right = render(scene) { x, y -> x to y - 3.0 } // content moved down 3 rows
        val estimate = AlignmentEstimator.estimate(left, right, width, height, 20)
        assertEquals(3.0, estimate!!.meanOffset, 0.3)
        assertEquals(0.0, estimate.slope, 0.005)
    }

    @Test
    fun horizontalDisparityIsIgnored() {
        val scene = Scene(2)
        val left = render(scene) { x, y -> x to y }
        val right = render(scene) { x, y -> x + 20.0 to y } // pure horizontal shift
        val estimate = AlignmentEstimator.estimate(left, right, width, height, 20)!!
        assertEquals(0.0, estimate.meanOffset, 0.2)
        assertEquals(0.0, estimate.slope, 0.003)
    }

    @Test
    fun verticalOffsetIsFoundDespiteDisparity() {
        val scene = Scene(4)
        val left = render(scene) { x, y -> x to y }
        val right = render(scene) { x, y -> x - 25.0 to y + 2.0 } // disparity plus content 2 rows higher
        val estimate = AlignmentEstimator.estimate(left, right, width, height, 20)!!
        assertEquals(-2.0, estimate.meanOffset, 0.2)
    }

    @Test
    fun clockwiseRightViewGivesPositiveSlope() {
        val scene = Scene(3)
        val angle = Math.toRadians(1.0)
        val left = render(scene) { x, y -> x to y }
        // Right view content rotated clockwise on screen (y down) about the image center.
        val right = render(scene) { x, y ->
            val dx = x - width / 2.0
            val dy = y - height / 2.0
            (width / 2.0 + cos(angle) * dx + sin(angle) * dy) to (height / 2.0 - sin(angle) * dx + cos(angle) * dy)
        }
        val estimate = AlignmentEstimator.estimate(left, right, width, height, 20)!!
        assertEquals(kotlin.math.tan(angle), estimate.slope, 0.006)
        assertEquals(0.0, estimate.meanOffset, 0.3)
    }
}
