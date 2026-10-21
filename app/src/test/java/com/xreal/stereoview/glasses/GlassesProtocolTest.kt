package com.xreal.stereoview.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class GlassesProtocolTest {
    private val chunkMap: IntArray = GlassesProtocol::class.java.getDeclaredField("CHUNK_MAP").let {
        it.isAccessible = true
        it.get(GlassesProtocol) as IntArray
    }

    /** Scrambles an image the way the glasses do, starting the cycle at [start]. */
    private fun scramble(image: ByteArray, start: Int): ByteArray {
        val frame = ByteArray(GlassesProtocol.FRAME_BYTES)
        for (i in 0 until 128) {
            val chunk = chunkMap[(start + i) % 128]
            System.arraycopy(image, i * 2400, frame, chunk * 2400, 2400)
        }
        return frame
    }

    @Test
    fun descrambleRestoresTheImageForAnyStart() {
        // The first image chunk must be the darkest, as the descrambler expects.
        val image = ByteArray(GlassesProtocol.IMAGE_BYTES) { (if (it < 2400) 0 else 40 + it % 200).toByte() }
        for (start in listOf(0, 1, 47, 127)) {
            assertArrayEquals("start $start", image, GlassesProtocol.descramble(scramble(image, start)))
        }
    }

    @Test
    fun frameMetadata() {
        val frame = ByteArray(GlassesProtocol.FRAME_BYTES)
        val meta = GlassesProtocol.IMAGE_BYTES
        val ts = 61_805_151_612L
        for (k in 0..7) frame[meta + k] = (ts ushr (8 * k)).toByte()
        frame[meta + 18] = 0x34
        frame[meta + 19] = 0x12
        frame[meta + 0x3B] = 1
        val info = GlassesProtocol.frameInfo(frame)
        assertEquals(ts, info.timestampNs)
        assertEquals(0x1234L, info.sequence)
        assertTrue(info.isRight)
    }

    @Test
    fun rotationsAreInverse() {
        val image = ByteArray(GlassesProtocol.IMAGE_BYTES) { (it * 31).toByte() }
        val left = GlassesProtocol.rotate(image, isRight = false)
        // Top-right pixel of the source becomes top-left after a counter-clockwise turn.
        assertEquals(image[639], left[0])
        val right = GlassesProtocol.rotate(image, isRight = true)
        // Bottom-left pixel of the source becomes top-left after a clockwise turn.
        assertEquals(image[479 * 640], right[0])
    }

    @Test
    fun sensorCommandLayout() {
        val cmd = GlassesProtocol.sensorCommand(GlassesProtocol.MSG_START_IMU_DATA, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0, 0, 0, 0, 4, 0, 0x19, 1).copyOfRange(5, 9), cmd.copyOfRange(5, 9))
        val crc = CRC32().apply { update(cmd, 5, 4) }.value
        assertEquals(crc, GlassesProtocol.le32(cmd, 1))
    }

    @Test
    fun parsesAnImuReport() {
        val r = ByteArray(64)
        r[0] = 1
        var p = 2
        fun put16(v: Int) { r[p] = v.toByte(); r[p + 1] = (v shr 8).toByte(); p += 2 }
        fun put32(v: Int) { for (k in 0..3) r[p + k] = (v shr (8 * k)).toByte(); p += 4 }
        fun put24(v: Int) { for (k in 0..2) r[p + k] = (v shr (8 * k)).toByte(); p += 3 }
        put16(-100) // temperature
        put32(1_000_000); put32(0) // timestamp
        put16(2000); put32(1 shl 23); put24(-4096); put24(0); put24(8192) // gyro
        put16(16); put32(1 shl 23); put24(524288); put24(0); put24(-524288) // accel
        val s = GlassesProtocol.parseImu(r, 64)
        assertNotNull(s)
        s!!
        assertEquals(1_000_000L, s.timestampNs)
        assertEquals(-100, s.temperatureRaw)
        assertEquals(-4096, s.gyro[0])
        assertEquals(8192, s.gyro[2])
        assertEquals(-524288, s.accel[2])
        assertEquals(1.0 * 9.80665, s.accelMps2(0), 1e-9) // 524288 * 16 / 2^23 = 1 g
    }
}
