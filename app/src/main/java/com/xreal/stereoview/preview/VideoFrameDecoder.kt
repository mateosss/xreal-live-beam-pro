package com.xreal.stereoview.preview

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.xreal.stereoview.record.MkvReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes frame i of a recording's video (frame i = row i of data.csv) with MediaCodec,
 * starting from the keyframe at or before it. Matroska files are read by [MkvReader], not by
 * Android's extractor, which cannot seek in files without a seek index on every device; MP4s
 * (H.265) go through MediaExtractor. Stepping forward reuses the running decoder, so playback
 * decodes each frame once. All channels are shown as recorded (chroma too, so encoder problems
 * stay visible). Not thread-safe: use from one thread.
 */
class VideoFrameDecoder(file: File) : Closeable {
    private val source: Source = if (file.name.endsWith(".mkv")) MkvSource(file) else ExtractorSource(file)
    private var codec: MediaCodec? = null
    private var nextFeed = 0
    private var lastOut = -1
    private var eosSent = false
    private val info = MediaCodec.BufferInfo()

    val frameCount: Int get() = source.count

    /** Frame [index] scaled to at most about [maxWidth] pixels wide, or null if it cannot be decoded. */
    fun frame(index: Int, maxWidth: Int): Bitmap? {
        if (index !in 0 until source.count) return null
        val c = codec ?: createCodec().also { codec = it; nextFeed = 0; lastOut = -1; eosSent = false }
        val key = source.keyframeAtOrBefore(index)
        if (eosSent || lastOut >= index || key > nextFeed) {
            c.flush()
            eosSent = false
            nextFeed = key
            lastOut = key - 1
        }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!eosSent) {
                val inIndex = c.dequeueInputBuffer(if (nextFeed <= index) 5_000 else 0)
                if (inIndex >= 0) {
                    val buffer = c.getInputBuffer(inIndex)!!
                    if (nextFeed < source.count) {
                        val data = source.sample(nextFeed)
                        buffer.clear()
                        if (data.size > buffer.capacity()) throw IOException("Frame $nextFeed is larger than the decoder's buffer")
                        buffer.put(data)
                        c.queueInputBuffer(inIndex, 0, data.size, nextFeed.toLong(), 0)
                        nextFeed++
                    } else {
                        c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
            }
            val outIndex = c.dequeueOutputBuffer(info, 5_000)
            if (outIndex < 0) continue
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size <= 0 && end) {
                c.releaseOutputBuffer(outIndex, false)
                return null
            }
            val frame = info.presentationTimeUs.toInt()
            lastOut = frame
            if (frame >= index) {
                val bitmap = try {
                    c.getOutputImage(outIndex)?.use { YuvToBitmap.convert(it, maxWidth) }
                } finally {
                    c.releaseOutputBuffer(outIndex, false)
                }
                if (bitmap == null) Log.w(TAG, "Decoder ${c.name} gave no image for frame $frame")
                return bitmap
            }
            c.releaseOutputBuffer(outIndex, false)
            if (end) return null
        }
        Log.w(TAG, "Timed out decoding frame $index")
        // Start over on the next request rather than reuse a stuck decoder.
        runCatching { c.release() }
        codec = null
        return null
    }

    /** A decoder for the source, software ones first: they behave the same on every device. */
    private fun createCodec(): MediaCodec {
        val format = source.format
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .sortedBy { if (isSoftware(it)) 0 else 1 }
        var error: Exception? = null
        for (info in candidates) {
            val c = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
            try {
                c.configure(format, null, null, 0)
                c.start()
                Log.i(TAG, "Decoding ${source.name} with ${info.name}")
                return c
            } catch (e: Exception) {
                error = e
                runCatching { c.release() }
            }
        }
        throw IOException("No decoder for $mime", error)
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        source.close()
    }

    private interface Source : Closeable {
        val name: String
        val format: MediaFormat
        val count: Int
        fun keyframeAtOrBefore(index: Int): Int

        /** Sample [index]; called with consecutive indices after a keyframe. */
        fun sample(index: Int): ByteArray
    }

    private class MkvSource(file: File) : Source {
        private val mkv = MkvReader.open(file)
        override val name: String = file.name
        override val count: Int get() = mkv.frames.size
        override val format: MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mkv.width, mkv.height).apply {
            val start = byteArrayOf(0, 0, 0, 1)
            setByteBuffer("csd-0", ByteBuffer.wrap(start + mkv.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(start + mkv.pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (mkv.frames.maxOfOrNull { it.size } ?: 0) + 1024)
        }
        override fun keyframeAtOrBefore(index: Int) = mkv.keyframeAtOrBefore(index)
        override fun sample(index: Int) = mkv.annexB(index)
        override fun close() = mkv.close()
    }

    /** MP4 via MediaExtractor: samples are in frame order (recordings have no B-frames). */
    private class ExtractorSource(file: File) : Source {
        private val extractor = MediaExtractor()
        override val name: String = file.name
        override val format: MediaFormat
        private val times: LongArray
        private val keys: BooleanArray
        private var position = 0
        private var buffer = ByteBuffer.allocate(1 shl 20)

        init {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("No video track in ${file.name}")
            extractor.selectTrack(track)
            format = extractor.getTrackFormat(track)
            val t = ArrayList<Long>()
            val k = ArrayList<Boolean>()
            while (extractor.sampleTime >= 0) {
                t += extractor.sampleTime
                k += extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (!extractor.advance()) break
            }
            times = t.toLongArray()
            keys = k.toBooleanArray()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        override val count: Int get() = times.size
        override fun keyframeAtOrBefore(index: Int): Int {
            var i = index
            while (i > 0 && !keys[i]) i--
            return i
        }

        override fun sample(index: Int): ByteArray {
            if (index != position) {
                extractor.seekTo(times[keyframeAtOrBefore(index)], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                position = times.indexOfFirst { it == extractor.sampleTime }.coerceAtLeast(0)
                while (position < index && extractor.advance()) position++
            }
            val size = extractor.sampleSize.toInt()
            if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size)
            buffer.clear()
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) throw IOException("No sample $index")
            val out = ByteArray(n)
            buffer.position(0)
            buffer.get(out, 0, n)
            extractor.advance()
            position++
            return out
        }

        override fun close() = extractor.release()
    }

    companion object {
        private const val TAG = "VideoFrameDecoder"
    }
}
