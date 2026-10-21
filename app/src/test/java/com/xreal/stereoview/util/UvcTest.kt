package com.xreal.stereoview.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UvcTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u16(v: Int) = intArrayOf(v and 0xff, v shr 8 and 0xff)
    private fun u32(v: Int) = intArrayOf(v and 0xff, v shr 8 and 0xff, v shr 16 and 0xff, v shr 24 and 0xff)

    /** A VideoStreaming interface with a GREY (Y8) format and one 640×480 frame at 30 and 60 fps. */
    @Test
    fun parsesUncompressedFormatAndFrames() {
        val iface = intArrayOf(9, 0x04, 4, 0, 1, 0x0e, 0x02, 0, 0)
        val guid = intArrayOf('G'.code, 'R'.code, 'E'.code, 'Y'.code, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)
        val format = intArrayOf(27, 0x24, 0x04, 1, 1) + guid + intArrayOf(8, 1, 0, 0, 0, 0)
        val frame = intArrayOf(34, 0x24, 0x05, 1, 0) + u16(640) + u16(480) + u32(0) + u32(0) + u32(640 * 480) +
            u32(333_333) + intArrayOf(2) + u32(333_333) + u32(166_666)
        val formats = Uvc.parseFormats(bytes(*(iface + format + frame)))
        assertEquals(1, formats.size)
        val f = formats[0]
        assertEquals(4, f.interfaceNumber)
        assertEquals("GREY", f.fourcc)
        assertEquals(8, f.bitsPerPixel)
        assertEquals(640, f.frames[0].width)
        assertEquals(480, f.frames[0].height)
        assertEquals(640 * 480, f.frames[0].maxFrameBytes)
        assertEquals(listOf(30.0, 60.0), f.frames[0].fps.map { Math.round(it).toDouble() })
    }
}
