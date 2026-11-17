package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MkvWriterTest {
    private fun hasFfmpeg() = runCatching { ProcessBuilder("ffprobe", "-version").start().waitFor() == 0 }.getOrDefault(false)

    private fun countFrames(f: File): Int {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", f.path).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim().lines().last().toInt()
    }

    @Test
    fun ebmlSizes() {
        assertEquals(listOf(0x81), MkvWriter.size(1).map { it.toInt() and 0xff })
        assertEquals(listOf(0x40, 0x7f), MkvWriter.size(127).map { it.toInt() and 0xff })
        assertEquals(8, MkvWriter.size8(5).size)
    }

    /** Muxes a real H.264 stream; the file plays fully, and so does a copy cut off midway (a crash). */
    @Test
    fun writesPlayableAndCrashSafeFiles() {
        assumeTrue(hasFfmpeg())
        val dir = Files.createTempDirectory("mkv").toFile()
        val h264 = File(dir, "in.h264")
        val gen = ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-frames:v", "90",
            "-c:v", "libx264", "-bf", "0", "-g", "30", "-x264-params", "aud=1", "-f", "h264", h264.path).start()
        assumeTrue(gen.waitFor() == 0)
        val data = h264.readBytes()
        // Split into access units at the access unit delimiters (NAL type 9).
        val nals = MkvWriter.nalUnits(data, 0, data.size)
        val units = ArrayList<MutableList<ByteArray>>()
        for (n in nals) {
            if ((n[0].toInt() and 0x1f) == 9 || units.isEmpty()) units += mutableListOf<ByteArray>()
            units.last() += n
        }
        val sps = nals.first { (it[0].toInt() and 0x1f) == 7 }
        val pps = nals.first { (it[0].toInt() and 0x1f) == 8 }
        val out = File(dir, "data.mkv")
        val w = MkvWriter(out, 320, 240, sps, pps)
        var crashCopy: File? = null
        units.forEachIndexed { i, au ->
            val bytes = au.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }
            val key = au.any { (it[0].toInt() and 0x1f) == 5 }
            w.writeFrame(i * 33_333L, bytes, 0, bytes.size, key)
            if (i == 49) crashCopy = File(dir, "crash.mkv").also { out.copyTo(it) }
        }
        w.close()
        assertEquals(90, countFrames(out))
        assertEquals(50, countFrames(crashCopy!!))
    }
}
