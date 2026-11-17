package com.xreal.stereoview.record

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.xreal.stereoview.util.PngGray
import java.io.File

/**
 * Stores 8-bit grayscale camera images (glasses and OAK mono cameras) as lossless PNG or as
 * JPEG. Android has no single-channel JPEG encoder, so JPEGs are encoded from YUV with the
 * gray image as luma and neutral chroma: the chroma costs almost nothing, and readers that load
 * them as grayscale (e.g. OpenCV IMREAD_GRAYSCALE) get exactly the luma.
 */
object GrayImages {
    private val yuv = ThreadLocal<ByteArray>()

    fun write(file: File, pixels: ByteArray, width: Int, height: Int, format: GrayImageFormat, jpegQuality: Int) {
        when (format) {
            GrayImageFormat.JPEG -> writeJpeg(file, pixels, width, height, jpegQuality)
            else -> PngGray.write(file, pixels, width, height)
        }
    }

    private fun writeJpeg(file: File, pixels: ByteArray, width: Int, height: Int, quality: Int) {
        val size = width * height
        // NV21: the Y plane, then interleaved V/U at quarter resolution, all 128 (neutral).
        val buffer = yuv.get()?.takeIf { it.size == size * 3 / 2 } ?: ByteArray(size * 3 / 2).also {
            it.fill(128.toByte(), size, it.size)
            yuv.set(it)
        }
        System.arraycopy(pixels, 0, buffer, 0, size)
        file.outputStream().buffered(1 shl 16).use {
            YuvImage(buffer, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, it)
        }
    }
}

/** How grayscale camera images are stored. */
enum class GrayImageFormat(val label: String, val extension: String) {
    PNG("PNG (lossless)", "png"),
    JPEG("JPEG (several times smaller; quality below)", "jpg"),
}
