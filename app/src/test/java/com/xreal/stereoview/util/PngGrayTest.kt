package com.xreal.stereoview.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

class PngGrayTest {
    /** Minimal decoder for what [PngGray] writes: checks chunk CRCs, inflates and undoes the Sub filter. */
    private fun decode(png: ByteArray, width: Int, height: Int): ByteArray {
        val input = DataInputStream(png.inputStream())
        input.skipBytes(8)
        val idat = ByteArrayOutputStream()
        while (true) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully)
            val body = ByteArray(length).also(input::readFully)
            val crc = input.readInt()
            assertEquals(CRC32().apply { update(type); update(body) }.value.toInt(), crc)
            when (String(type)) {
                "IHDR" -> {
                    assertEquals(width, DataInputStream(body.inputStream()).readInt())
                    assertEquals(0, body[9].toInt()) // grayscale
                }
                "IDAT" -> idat.write(body)
                "IEND" -> break
            }
        }
        val raw = ByteArray(height * (width + 1))
        Inflater().apply { setInput(idat.toByteArray()); inflate(raw); end() }
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * (width + 1)
            assertEquals(1, raw[row].toInt())
            for (x in 0 until width) {
                val left = if (x > 0) out[y * width + x - 1] else 0
                out[y * width + x] = (raw[row + 1 + x] + left).toByte()
            }
        }
        return out
    }

    @Test
    fun roundTrips() {
        val (w, h) = 37 to 11
        val pixels = ByteArray(w * h) { ((it * 7) xor (it / w * 13)).toByte() }
        val bytes = ByteArrayOutputStream().also { PngGray.write(it, pixels, w, h) }.toByteArray()
        assertArrayEquals(pixels, decode(bytes, w, h))
    }
}
