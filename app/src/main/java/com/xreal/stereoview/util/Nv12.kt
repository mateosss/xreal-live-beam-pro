package com.xreal.stereoview.util

/**
 * NV12 images (a Y plane, then interleaved U, V at half resolution), as the OAK color camera
 * makes them, to RGB. Full-range BT.601 (JFIF), as the camera's own JPEGs.
 */
object Nv12 {
    /** [width]×[height] RGB bytes (R, G, B per pixel) into [out] (allocated if null or too small). */
    fun toRgb(nv12: ByteArray, width: Int, height: Int, out: ByteArray? = null): ByteArray {
        val rgb = out?.takeIf { it.size >= width * height * 3 } ?: ByteArray(width * height * 3)
        val uv0 = width * height
        for (y in 0 until height) {
            val yRow = y * width
            val uvRow = uv0 + (y shr 1) * width
            var o = yRow * 3
            for (x in 0 until width) {
                val l = nv12[yRow + x].toInt() and 0xFF
                val c = uvRow + (x and 1.inv())
                val u = (nv12[c].toInt() and 0xFF) - 128
                val v = (nv12[c + 1].toInt() and 0xFF) - 128
                rgb[o] = clamp(l + ((91881 * v) shr 16)).toByte()
                rgb[o + 1] = clamp(l - ((22554 * u + 46802 * v) shr 16)).toByte()
                rgb[o + 2] = clamp(l + ((116130 * u) shr 16)).toByte()
                o += 3
            }
        }
        return rgb
    }

    /** ARGB pixels of the image at 1/[step] size (each [step]th pixel), for previews. */
    fun toArgb(nv12: ByteArray, width: Int, height: Int, step: Int): IntArray {
        val w = width / step
        val h = height / step
        val argb = IntArray(w * h)
        val uv0 = width * height
        var o = 0
        for (yy in 0 until h) {
            val y = yy * step
            val yRow = y * width
            val uvRow = uv0 + (y shr 1) * width
            for (xx in 0 until w) {
                val x = xx * step
                val l = nv12[yRow + x].toInt() and 0xFF
                val c = uvRow + (x and 1.inv())
                val u = (nv12[c].toInt() and 0xFF) - 128
                val v = (nv12[c + 1].toInt() and 0xFF) - 128
                val r = clamp(l + ((91881 * v) shr 16))
                val g = clamp(l - ((22554 * u + 46802 * v) shr 16))
                val b = clamp(l + ((116130 * u) shr 16))
                argb[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return argb
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
