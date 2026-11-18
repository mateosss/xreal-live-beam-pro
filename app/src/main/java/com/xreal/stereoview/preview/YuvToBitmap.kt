package com.xreal.stereoview.preview

import android.graphics.Bitmap
import android.media.Image

/**
 * Converts a decoded YUV_420_888 [Image] (any plane layout) to an RGB bitmap, keeping every
 * n-th pixel so the result is at most about maxWidth wide. BT.601 limited range, as camera
 * encoders produce; grayscale recordings (neutral chroma) come out exactly gray.
 */
object YuvToBitmap {
    fun convert(image: Image, maxWidth: Int): Bitmap {
        val crop = image.cropRect
        val step = ((crop.width() + maxWidth - 1) / maxWidth.coerceAtLeast(1)).coerceAtLeast(1)
        val w = crop.width() / step
        val h = crop.height() / step
        val (yPlane, uPlane, vPlane) = image.planes
        val y = bytes(yPlane.buffer)
        val u = bytes(uPlane.buffer)
        val v = bytes(vPlane.buffer)
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val cRow = uPlane.rowStride
        val cPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        val out = IntArray(w * h)
        for (r in 0 until h) {
            val sy = crop.top + r * step
            val cy = sy / 2
            for (col in 0 until w) {
                val sx = crop.left + col * step
                val cx = sx / 2
                val yy = ((y.getOrElse(sy * yRow + sx * yPix) { 0 }.toInt() and 0xff) - 16).coerceAtLeast(0) * 1192
                val uu = (u.getOrElse(cy * cRow + cx * cPix) { -128 }.toInt() and 0xff) - 128
                val vv = (v.getOrElse(cy * vRow + cx * vPix) { -128 }.toInt() and 0xff) - 128
                val red = (yy + 1634 * vv) shr 10
                val green = (yy - 833 * vv - 400 * uu) shr 10
                val blue = (yy + 2066 * uu) shr 10
                out[r * w + col] = (0xff shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun bytes(b: java.nio.ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.position(0)
        return ByteArray(d.remaining()).also { d.get(it) }
    }
}
