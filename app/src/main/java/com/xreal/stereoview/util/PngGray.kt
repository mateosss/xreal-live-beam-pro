package com.xreal.stereoview.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Writes 8-bit grayscale PNGs (lossless, as EuRoC-style datasets expect). */
object PngGray {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun write(file: File, pixels: ByteArray, width: Int, height: Int, level: Int = Deflater.BEST_SPEED) {
        file.outputStream().buffered(1 shl 16).use { write(it, pixels, width, height, level) }
    }

    fun write(out: OutputStream, pixels: ByteArray, width: Int, height: Int, level: Int = Deflater.BEST_SPEED) {
        // Each row is prefixed with its filter type; "Sub" (1) stores differences to the left
        // neighbour, which compresses camera images much better than storing them as they are.
        val raw = ByteArray(height * (width + 1))
        for (y in 0 until height) {
            val src = y * width
            val dst = y * (width + 1)
            raw[dst] = 1
            raw[dst + 1] = pixels[src]
            for (x in 1 until width) raw[dst + 1 + x] = (pixels[src + x] - pixels[src + x - 1]).toByte()
        }
        val deflater = Deflater(level)
        val compressed = ByteArrayOutputStream(raw.size / 2)
        deflater.setInput(raw)
        deflater.finish()
        val chunk = ByteArray(1 shl 16)
        while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()

        val data = DataOutputStream(out)
        data.write(SIGNATURE)
        val header = ByteArrayOutputStream(13).also {
            DataOutputStream(it).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8) // bit depth
                writeByte(0) // grayscale
                writeByte(0) // deflate
                writeByte(0) // adaptive filtering
                writeByte(0) // no interlace
            }
        }.toByteArray()
        chunk(data, "IHDR", header)
        chunk(data, "IDAT", compressed.toByteArray())
        chunk(data, "IEND", ByteArray(0))
        data.flush()
    }

    private fun chunk(out: DataOutputStream, type: String, body: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(body.size)
        out.write(typeBytes)
        out.write(body)
        out.writeInt(CRC32().apply { update(typeBytes); update(body) }.value.toInt())
    }
}
