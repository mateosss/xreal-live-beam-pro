package com.xreal.stereoview.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.Inflater

class Nv12PngTest {
    /** 4×2 NV12: gray, white and a red-ish pixel pair; chroma per 2×2 block. */
    private fun sample(): ByteArray {
        val w = 4
        val h = 2
        val y = byteArrayOf(128.toByte(), 128.toByte(), 81, 81, 128.toByte(), 128.toByte(), 81, 81)
        // Block 0 neutral; block 1: red in full-range BT.601 (Y 76, U 85, V 255) with Y 81.
        val uv = byteArrayOf(128.toByte(), 128.toByte(), 90, 240.toByte())
        return y + uv.also { assertEquals(w * h / 2, it.size) }
    }

    @Test
    fun nv12ToRgbIsFullRangeBt601() {
        val rgb = Nv12.toRgb(sample(), 4, 2)
        // Neutral chroma: gray stays gray.
        assertEquals(listOf(128, 128, 128), (0..2).map { rgb[it].toInt() and 0xFF })
        // Strong V, weak U: red dominant, blue lowest.
        val (r, g, b) = (6..8).map { rgb[it].toInt() and 0xFF }
        assert(r > 200 && g < 60 && b < 60) { "$r $g $b" }
        val argb = Nv12.toArgb(sample(), 4, 2, 2)
        assertEquals(2, argb.size)
        assertEquals(0xFF808080.toInt(), argb[0])
    }

    @Test
    fun rgbPngReadsBackExactly() {
        val w = 37
        val h = 5
        val rgb = ByteArray(w * h * 3) { ((it * 31) xor (it shr 3)).toByte() }
        val f = File.createTempFile("rgb", ".png")
        try {
            PngGray.writeRgb(f, rgb, w, h)
            // Minimal decode: IHDR says truecolor; IDAT inflates to Sub-filtered rows of 3-byte pixels.
            val png = f.readBytes()
            val input = java.io.DataInputStream(png.inputStream()).apply { skipBytes(8) }
            val idat = java.io.ByteArrayOutputStream()
            while (true) {
                val length = input.readInt()
                val type = String(ByteArray(4).also(input::readFully))
                val body = ByteArray(length).also(input::readFully)
                input.readInt()
                if (type == "IHDR") assertEquals(2, body[9].toInt())
                if (type == "IDAT") idat.write(body)
                if (type == "IEND") break
            }
            val row = w * 3 + 1
            val raw = ByteArray(h * row)
            Inflater().apply { setInput(idat.toByteArray()); inflate(raw); end() }
            val out = ByteArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w * 3) {
                val left = if (x >= 3) out[y * w * 3 + x - 3] else 0
                out[y * w * 3 + x] = (raw[y * row + 1 + x] + left).toByte()
            }
            org.junit.Assert.assertArrayEquals(rgb, out)
        } finally {
            f.delete()
        }
    }
}
