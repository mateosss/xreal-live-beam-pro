package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectEventTest {
    @Test
    fun parsesTheSharedMemoryLayout() {
        val bytes = ByteArray(DirectEvent.SIZE)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, DirectEvent.SIZE)
            putInt(4, 7)
            putInt(8, 4)
            putInt(12, 0xfffffffe.toInt())
            putLong(16, 123_456_789_012L)
            putFloat(24, 1.5f)
            putFloat(28, -2f)
            putFloat(32, 9.81f)
        }
        val e = DirectEvent()
        DirectEvent.parse(bytes, e)
        assertEquals(7, e.token)
        assertEquals(4, e.type)
        assertEquals(0xfffffffeL, e.counter)
        assertEquals(123_456_789_012L, e.timestampNs)
        assertEquals(1.5f, e.values[0])
        assertEquals(-2f, e.values[1])
        assertEquals(9.81f, e.values[2])
    }

    @Test
    fun counterWrapsPastZero() {
        assertEquals(1L, DirectEvent.next(0xffffffffL))
        assertEquals(6L, DirectEvent.next(5L))
        assertTrue(DirectEvent.isAfter(2L, 0xfffffff0L))
        assertFalse(DirectEvent.isAfter(0xfffffff0L, 2L))
        assertFalse(DirectEvent.isAfter(5L, 5L))
        assertEquals(3L, DirectEvent.distance(5L, 8L))
    }
}
