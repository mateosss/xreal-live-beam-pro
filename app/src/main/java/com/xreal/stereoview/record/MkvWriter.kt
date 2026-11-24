package com.xreal.stereoview.record

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A minimal Matroska (MKV) writer for one H.264 video track, built to survive crashes: the
 * Segment and each Cluster are written with "unknown size" and every frame is appended as a
 * SimpleBlock as soon as it is encoded, so a file cut off at any point still plays (ffmpeg,
 * VLC, OpenCV) up to its last complete frame. [close] patches in the real sizes, making a
 * finished file fully standard. A new Cluster starts at every keyframe (and at least every 30 s).
 *
 * Frames are given as Annex-B (start codes, as MediaCodec outputs them) or length-prefixed;
 * they are stored length-prefixed with the SPS/PPS in CodecPrivate (avcC), as Matroska expects.
 * Container timestamps have 1 ms resolution (Matroska's usual scale); the exact ns timestamps
 * are in data.csv.
 */
class MkvWriter(private val file: File, private val width: Int, private val height: Int, sps: ByteArray, pps: ByteArray) {
    private val out = FileOutputStream(file)
    private var position = 0L
    private val segmentSizeAt: Long
    private val segmentDataStart: Long
    private var clusterSizeAt = -1L
    private var clusterDataStart = 0L
    private var clusterTimeMs = 0L
    private var firstUs = Long.MIN_VALUE
    private val clusters = ArrayList<Pair<Long, Long>>() // size field position, data start
    var frames = 0
        private set

    init {
        write(element(EBML, children(
            uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
            string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2),
        )))
        write(id(SEGMENT))
        segmentSizeAt = position
        write(UNKNOWN_SIZE)
        segmentDataStart = position
        write(element(INFO, children(
            uint(0x2AD7B1, TIMESCALE_NS), // TimestampScale: 1 ms per tick
            string(0x4D80, "XREAL Stereo"), string(0x5741, "XREAL Stereo"),
        )))
        val video = element(0xE0, children(uint(0xB0, width.toLong()), uint(0xBA, height.toLong())))
        write(element(TRACKS, element(0xAE, children(
            uint(0xD7, 1), uint(0x73C5, 1), uint(0x83, 1), uint(0x9C, 0),
            string(0x86, "V_MPEG4/ISO/AVC"), binary(0x63A2, avcC(sps, pps)), video,
        ))))
    }

    /** Appends one frame at [ptsUs] (increasing). */
    @Synchronized
    fun writeFrame(ptsUs: Long, data: ByteArray, offset: Int, length: Int, keyframe: Boolean) {
        if (firstUs == Long.MIN_VALUE) firstUs = ptsUs
        val t = (ptsUs - firstUs + 500) / 1000 // ms since the first frame
        // Block times are 16-bit offsets from their cluster's: start one per keyframe, and at
        // the latest after 30 s.
        if (clusterSizeAt < 0 || keyframe || t - clusterTimeMs > 30_000L) startCluster(t)
        val payload = toLengthPrefixed(data, offset, length)
        val rel = (t - clusterTimeMs).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
        val header = byteArrayOf(0x81.toByte(), (rel shr 8).toByte(), rel.toByte(), if (keyframe) 0x80.toByte() else 0)
        write(id(SIMPLE_BLOCK))
        write(size(header.size.toLong() + payload.size))
        write(header)
        write(payload)
        frames++
    }

    private fun startCluster(t: Long) {
        write(id(CLUSTER))
        clusterSizeAt = position
        write(UNKNOWN_SIZE)
        clusterDataStart = position
        clusterTimeMs = t
        write(uint(0xE7, t))
        clusters += clusterSizeAt to clusterDataStart
    }

    /** Forces data to storage (call every few seconds). */
    @Synchronized
    fun sync() {
        runCatching { out.fd.sync() }
    }

    /** Patches the Segment and Cluster sizes and closes the file. */
    @Synchronized
    fun close() {
        out.flush()
        out.fd.sync()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            val end = raf.length()
            for ((i, c) in clusters.withIndex()) {
                val next = if (i + 1 < clusters.size) clusters[i + 1].first - CLUSTER_ID_LENGTH else end
                raf.seek(c.first)
                raf.write(size8(next - c.second))
            }
            raf.seek(segmentSizeAt)
            raf.write(size8(end - segmentDataStart))
        }
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    companion object {
        private const val EBML = 0x1A45DFA3
        private const val SEGMENT = 0x18538067
        private const val INFO = 0x1549A966
        private const val TRACKS = 0x1654AE6B
        private const val CLUSTER = 0x1F43B675
        private const val CLUSTER_ID_LENGTH = 4
        private const val SIMPLE_BLOCK = 0xA3
        private const val TIMESCALE_NS = 1_000_000L

        /** 8-byte "unknown size", patched by [close]. */
        private val UNKNOWN_SIZE = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        fun id(id: Int): ByteArray = when {
            id >= 0x1000000 -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
            id >= 0x10000 -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
            id >= 0x100 -> byteArrayOf((id shr 8).toByte(), id.toByte())
            else -> byteArrayOf(id.toByte())
        }

        /** EBML variable-length size, shortest form. */
        fun size(n: Long): ByteArray {
            var length = 1
            while (n >= (1L shl (7 * length)) - 1) length++
            val b = ByteArray(length)
            for (i in 0 until length) b[length - 1 - i] = (n shr (8 * i)).toByte()
            b[0] = (b[0].toInt() or (0x80 shr (length - 1))).toByte()
            return b
        }

        /** EBML size in exactly 8 bytes (patchable). */
        fun size8(n: Long): ByteArray {
            val b = ByteArray(8)
            for (i in 0 until 8) b[7 - i] = (n shr (8 * i)).toByte()
            b[0] = 0x01
            return b
        }

        private fun element(id: Int, body: ByteArray) = id(id) + size(body.size.toLong()) + body
        private fun children(vararg c: ByteArray): ByteArray = ByteArrayOutputStream().apply { c.forEach { write(it) } }.toByteArray()
        private fun string(id: Int, s: String) = element(id, s.toByteArray(Charsets.US_ASCII))
        private fun binary(id: Int, b: ByteArray) = element(id, b)
        private fun uint(id: Int, v: Long): ByteArray {
            var length = 1
            while (length < 8 && v ushr (8 * length) != 0L) length++
            return element(id, ByteArray(length) { (v shr (8 * (length - 1 - it))).toByte() })
        }

        /** AVCDecoderConfigurationRecord from one SPS and one PPS (with or without start codes). */
        fun avcC(spsIn: ByteArray, ppsIn: ByteArray): ByteArray {
            val sps = nalUnits(spsIn, 0, spsIn.size).first()
            val pps = nalUnits(ppsIn, 0, ppsIn.size).first()
            return ByteArrayOutputStream().apply {
                write(1)
                write(sps[1].toInt())
                write(sps[2].toInt())
                write(sps[3].toInt())
                write(0xFF) // 4-byte NAL lengths
                write(0xE1) // one SPS
                write(sps.size shr 8); write(sps.size)
                write(sps)
                write(1)
                write(pps.size shr 8); write(pps.size)
                write(pps)
            }.toByteArray()
        }

        /** NAL units of an Annex-B buffer (without start codes); a buffer without start codes is one NAL. */
        fun nalUnits(data: ByteArray, offset: Int, length: Int): List<ByteArray> {
            val end = offset + length
            val starts = ArrayList<Pair<Int, Int>>() // start-code position, payload start
            var i = offset
            while (i + 2 < end) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                    if (i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
                }
                i++
            }
            if (starts.isEmpty()) return listOf(data.copyOfRange(offset, end))
            return starts.mapIndexed { k, (_, payload) ->
                val stop = if (k + 1 < starts.size) starts[k + 1].first else end
                data.copyOfRange(payload, stop)
            }.filter { it.isNotEmpty() }
        }

        /** Annex-B → 4-byte length prefixes; already length-prefixed data is kept as it is. */
        fun toLengthPrefixed(data: ByteArray, offset: Int, length: Int): ByteArray {
            val annexB = length >= 4 && data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 &&
                (data[offset + 2].toInt() == 1 || (data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1))
            if (!annexB) return data.copyOfRange(offset, offset + length)
            val out = ByteArrayOutputStream(length + 16)
            for (nal in nalUnits(data, offset, length)) {
                out.write(nal.size ushr 24); out.write(nal.size ushr 16); out.write(nal.size ushr 8); out.write(nal.size)
                out.write(nal)
            }
            return out.toByteArray()
        }
    }
}
