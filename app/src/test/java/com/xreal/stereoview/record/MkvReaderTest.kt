package com.xreal.stereoview.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class MkvReaderTest {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1e, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0x33, 0x44)

    /** Annex-B frame i: an IDR slice every 10 frames, else a P slice; sizes vary (incl. 256..511 bytes). */
    private fun frame(i: Int): ByteArray {
        val type = if (i % 10 == 0) 0x65 else 0x41
        val body = ByteArray(5 + i * 37 % 600) { (it * 7 + i).toByte() }
        body[0] = type.toByte()
        return byteArrayOf(0, 0, 0, 1) + body
    }

    private fun write(f: File, n: Int, close: Boolean): MkvWriter {
        val w = MkvWriter(f, 640, 480, byteArrayOf(0, 0, 0, 1) + sps, byteArrayOf(0, 0, 0, 1) + pps)
        for (i in 0 until n) { val d = frame(i); w.writeFrame(1_000_000L + i * 33_333L, d, 0, d.size, i % 10 == 0) }
        if (close) w.close() else w.sync()
        return w
    }

    private fun check(f: File, expected: Int) {
        MkvReader.open(f).use { r ->
            assertEquals(640, r.width)
            assertEquals(480, r.height)
            assertArrayEquals(sps, r.sps)
            assertArrayEquals(pps, r.pps)
            assertEquals(expected, r.frames.size)
            for (i in 0 until expected) {
                assertArrayEquals("frame $i", frame(i), r.annexB(i))
                assertEquals(i % 10 == 0, r.frames[i].keyframe)
                assertEquals((i * 33_333L + 500) / 1000, r.frames[i].timeMs)
            }
            assertEquals(20, r.keyframeAtOrBefore(27))
            assertEquals(0, r.keyframeAtOrBefore(0))
        }
    }

    @Test
    fun readsFinishedFiles() {
        val f = File(Files.createTempDirectory("mkvr").toFile(), "data.mkv")
        write(f, 95, close = true)
        check(f, 95)
    }

    @Test
    fun readsFilesNeverClosed() {
        val f = File(Files.createTempDirectory("mkvr").toFile(), "data.mkv")
        write(f, 95, close = false)
        check(f, 95)
    }

    @Test
    fun readsFilesCutOffMidFrame() {
        val dir = Files.createTempDirectory("mkvr").toFile()
        val f = File(dir, "data.mkv")
        write(f, 95, close = true)
        val cut = File(dir, "cut.mkv")
        f.copyTo(cut)
        RandomAccessFile(cut, "rw").use { it.setLength(it.length() - 100) } // the last frame is incomplete
        MkvReader.open(cut).use { assertEquals(94, it.frames.size) }
        assertTrue(cut.length() > 0)
    }
}
