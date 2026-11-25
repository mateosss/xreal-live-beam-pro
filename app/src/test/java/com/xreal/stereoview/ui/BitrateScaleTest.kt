package com.xreal.stereoview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateScaleTest {
    @Test
    fun scaleIsLogarithmicFrom100kbitTo100Mbit() {
        assertEquals(0f, BitrateScale.toPosition(0.1f), 1e-6f)
        assertEquals(1f, BitrateScale.toPosition(100f), 1e-6f)
        // Each decade is a third of the slider.
        assertEquals(1f / 3, BitrateScale.toPosition(1f), 1e-5f)
        assertEquals(2f / 3, BitrateScale.toPosition(10f), 1e-5f)
        for (v in listOf(0.1f, 0.37f, 3f, 55f, 100f)) assertEquals(v, BitrateScale.fromPosition(BitrateScale.toPosition(v)), v * 1e-4f)
    }

    @Test
    fun everyPointIsMagneticAndTheGapsAreNot() {
        for (p in BitrateScale.POINTS) {
            val at = BitrateScale.toPosition(p)
            assertEquals(p, BitrateScale.atPosition(at))
            assertEquals(p, BitrateScale.atPosition(at + BitrateScale.SNAP * 0.9f))
        }
        // Between 20 and 40, far from both: a free value, two significant digits.
        val free = BitrateScale.atPosition(BitrateScale.toPosition(28f))
        assertNull(BitrateScale.magnet(BitrateScale.toPosition(28f)))
        assertEquals(28f, free)
        // Neighbouring points' snap zones don't overlap.
        val positions = BitrateScale.POINTS.map(BitrateScale::toPosition)
        for ((a, b) in positions.zipWithNext()) assertTrue(b - a > 2 * BitrateScale.SNAP)
    }

    @Test
    fun formatsInKbitBelowOneMbit() {
        assertEquals("100 kbit/s", BitrateScale.format(0.1f))
        assertEquals("250 kbit/s", BitrateScale.format(0.25f))
        assertEquals("1 Mbit/s", BitrateScale.format(1f))
        assertEquals("2.5 Mbit/s", BitrateScale.format(2.5f))
        assertEquals("40 Mbit/s", BitrateScale.format(40f))
        assertEquals("0.25", BitrateScale.number(0.25f))
        assertEquals("12", BitrateScale.number(12f))
    }

    @Test
    fun parsesTypedRates() {
        assertEquals(2.5f, BitrateScale.parse("2.5"))
        assertEquals(0.5f, BitrateScale.parse("0,5"))
        assertEquals(0.25f, BitrateScale.parse("250k"))
        assertEquals(0.25f, BitrateScale.parse("250 kbps"))
        assertEquals(4f, BitrateScale.parse(" 4 Mbit/s "))
        assertEquals(BitrateScale.TYPED_MAX, BitrateScale.parse("100000"))
        assertEquals(BitrateScale.TYPED_MIN, BitrateScale.parse("0.001"))
        assertNull(BitrateScale.parse(""))
        assertNull(BitrateScale.parse("abc"))
        assertNull(BitrateScale.parse("0"))
    }
}
