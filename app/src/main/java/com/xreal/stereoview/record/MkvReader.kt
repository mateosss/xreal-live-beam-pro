package com.xreal.stereoview.record

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Indexes the H.264 frames of a Matroska file without Android's extractor: recordings are
 * written by [MkvWriter] with no seek index (Cues) and, after a crash, with unknown sizes and a
 * cut-off last frame, which platform extractors handle differently from device to device.
 *
 * The file is scanned once, reading only element headers, so it is fast even for hours of
 * video. Frame i of [frames] is row i of data.csv. Plain java.io, so it is unit-tested on the JVM.
 */
class MkvReader private constructor(
    private val raf: RandomAccessFile,
    val width: Int,
    val height: Int,
    /** SPS and PPS (without start codes), from the track's avcC. */
    val sps: ByteArray,
    val pps: ByteArray,
    val frames: List<Frame>,
) : AutoCloseable {
    /** One frame's data in the file (length-prefixed NAL units) and its container time. */
    class Frame(val offset: Long, val size: Int, val timeMs: Long, val keyframe: Boolean)

    /** Frame [index] in Annex-B form (start codes), as MediaCodec decoders take it. */
    @Synchronized
    fun annexB(index: Int): ByteArray {
        val f = frames[index]
        val b = ByteArray(f.size)
        raf.seek(f.offset)
        raf.readFully(b)
        // 4-byte lengths → 4-byte start codes, in place.
        var i = 0
        while (i + 4 <= b.size) {
            val n = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
                ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            b[i] = 0; b[i + 1] = 0; b[i + 2] = 0; b[i + 3] = 1
            if (n <= 0) break
            i += 4 + n
        }
        return b
    }

    /** The keyframe at or before frame [index] (0 if there is none). */
    fun keyframeAtOrBefore(index: Int): Int {
        var k = index.coerceAtMost(frames.size - 1)
        while (k > 0 && !frames[k].keyframe) k--
        return k.coerceAtLeast(0)
    }

    override fun close() = raf.close()

    companion object {
        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val CLUSTER = 0x1F43B675L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val VIDEO = 0xE0L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val CLUSTER_TIME = 0xE7L
        private const val CODEC_ID = 0x86L
        private const val CODEC_PRIVATE = 0x63A2L
        private const val PIXEL_WIDTH = 0xB0L
        private const val PIXEL_HEIGHT = 0xBAL
        private const val TIMESCALE = 0x2AD7B1L
        private const val INFO = 0x1549A966L

        /** Elements whose children are read in place (sizes may be unknown in a cut-off file). */
        private val MASTERS = setOf(SEGMENT, CLUSTER, TRACKS, TRACK_ENTRY, VIDEO, BLOCK_GROUP, INFO)

        fun open(file: File): MkvReader {
            val raf = RandomAccessFile(file, "r")
            try {
                val length = raf.length()
                var width = 0
                var height = 0
                var codec = ""
                var codecPrivate: ByteArray? = null
                var timescaleNs = 1_000_000L
                var clusterTime = 0L
                val frames = ArrayList<Frame>()
                var pos = 0L
                while (pos < length) {
                    raf.seek(pos)
                    val id = readId(raf) ?: break
                    val size = readSize(raf) ?: break
                    val dataStart = raf.filePointer
                    if (id in MASTERS) { pos = dataStart; continue }
                    // A size running past the end: the file was cut off here.
                    if (size < 0 || dataStart + size > length) break
                    when (id) {
                        CODEC_ID -> codec = String(read(raf, size), Charsets.US_ASCII).trimEnd('\u0000')
                        CODEC_PRIVATE -> codecPrivate = read(raf, size)
                        PIXEL_WIDTH -> width = readUInt(raf, size).toInt()
                        PIXEL_HEIGHT -> height = readUInt(raf, size).toInt()
                        TIMESCALE -> timescaleNs = readUInt(raf, size)
                        CLUSTER_TIME -> clusterTime = readUInt(raf, size)
                        SIMPLE_BLOCK, BLOCK -> {
                            // Track number (vint), 16-bit relative time, flags; no lacing in recordings.
                            val trackLength = vintLength(raf.read())
                            raf.seek(dataStart + trackLength)
                            val rel = raf.readShort().toLong()
                            val flags = raf.read()
                            val headerLength = trackLength + 3
                            val payload = (size - headerLength).toInt()
                            if (payload > 0 && flags and 0x06 == 0) {
                                val offset = dataStart + headerLength
                                val key = (id == SIMPLE_BLOCK && flags and 0x80 != 0) || hasIdr(raf, offset, payload)
                                frames += Frame(offset, payload, (clusterTime + rel) * timescaleNs / 1_000_000L, key)
                            }
                        }
                    }
                    pos = dataStart + size
                }
                if (codec.isNotEmpty() && codec != "V_MPEG4/ISO/AVC") throw IOException("Unsupported codec $codec")
                val avcC = codecPrivate ?: throw IOException("No H.264 decoder configuration in ${file.name}")
                val (sps, pps) = parseAvcC(avcC)
                return MkvReader(raf, width, height, sps, pps, frames)
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }

        /** SPS and PPS of an AVCDecoderConfigurationRecord. */
        fun parseAvcC(c: ByteArray): Pair<ByteArray, ByteArray> {
            if (c.size < 7 || c[0].toInt() != 1) throw IOException("Bad avcC")
            var i = 5
            val spsCount = c[i++].toInt() and 0x1f
            var sps: ByteArray? = null
            repeat(spsCount) {
                val n = ((c[i].toInt() and 0xff) shl 8) or (c[i + 1].toInt() and 0xff)
                if (sps == null) sps = c.copyOfRange(i + 2, i + 2 + n)
                i += 2 + n
            }
            val ppsCount = c[i++].toInt() and 0xff
            var pps: ByteArray? = null
            repeat(ppsCount) {
                val n = ((c[i].toInt() and 0xff) shl 8) or (c[i + 1].toInt() and 0xff)
                if (pps == null) pps = c.copyOfRange(i + 2, i + 2 + n)
                i += 2 + n
            }
            return (sps ?: throw IOException("No SPS")) to (pps ?: throw IOException("No PPS"))
        }

        /** Whether a length-prefixed frame contains an IDR slice (NAL type 5). */
        private fun hasIdr(raf: RandomAccessFile, offset: Long, size: Int): Boolean {
            var p = 0L
            while (p + 5 <= size) {
                raf.seek(offset + p)
                val n = raf.readInt().toLong() and 0xffffffffL
                val type = raf.read() and 0x1f
                if (type == 5) return true
                if (n <= 0) return false
                p += 4 + n
            }
            return false
        }

        private fun vintLength(first: Int): Int {
            if (first <= 0) return 1
            var mask = 0x80
            var length = 1
            while (length <= 8 && first and mask == 0) { mask = mask shr 1; length++ }
            return length
        }

        private fun readId(raf: RandomAccessFile): Long? {
            val first = raf.read()
            if (first <= 0) return null
            val length = vintLength(first)
            if (length > 4) return null
            var v = first.toLong()
            repeat(length - 1) { val b = raf.read(); if (b < 0) return null; v = (v shl 8) or b.toLong() }
            return v
        }

        /** Element data size, or -1 for "unknown". Null at the end of the file. */
        private fun readSize(raf: RandomAccessFile): Long? {
            val first = raf.read()
            if (first <= 0) return null
            val length = vintLength(first)
            if (length > 8) return null
            var v = (first and (0xff shr length)).toLong()
            var allOnes = v == (0xff shr length).toLong()
            repeat(length - 1) {
                val b = raf.read()
                if (b < 0) return null
                if (b != 0xff) allOnes = false
                v = (v shl 8) or b.toLong()
            }
            return if (allOnes) -1 else v
        }

        private fun read(raf: RandomAccessFile, size: Long): ByteArray = ByteArray(size.toInt()).also { raf.readFully(it) }

        private fun readUInt(raf: RandomAccessFile, size: Long): Long {
            var v = 0L
            repeat(size.toInt()) { v = (v shl 8) or raf.read().toLong() }
            return v
        }
    }
}
