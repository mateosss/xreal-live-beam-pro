package com.xreal.stereoview.preview

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.xreal.stereoview.record.DatasetTrimmer
import com.xreal.stereoview.record.MkvReader
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cuts a recording's MP4 by frame index without re-encoding: copies the samples from the
 * keyframe at or before the first wanted frame through the last one. Recordings have no
 * B-frames, so sample order is frame order and sample i is row i of data.csv.
 */
object Mp4Cutter : DatasetTrimmer.VideoCutter {
    override fun cut(source: File, target: File, first: Int, last: Int): Int {
        if (source.name.endsWith(".mkv")) return cutMkv(source, target, first, last)
        val keyframe = keyframeAtOrBefore(source, first)
        if (target.name.endsWith(".mkv")) return cutToMkv(source, target, keyframe, last)
        val extractor = MediaExtractor()
        extractor.setDataSource(source.path)
        val track = videoTrack(extractor)
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
            val out = muxer.addTrack(format)
            muxer.start()
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 8 shl 20
            var buffer = ByteBuffer.allocateDirect(size)
            val info = MediaCodec.BufferInfo()
            var index = 0
            while (index <= last) {
                if (index >= keyframe) {
                    if (extractor.sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(extractor.sampleSize.toInt())
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, n, extractor.sampleTime, flags)
                    muxer.writeSampleData(out, buffer, info)
                }
                index++
                if (!extractor.advance()) break
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
        return keyframe
    }

    /** Same cut into Matroska (H.264 recordings), with [com.xreal.stereoview.record.MkvWriter]. */
    private fun cutToMkv(source: File, target: File, keyframe: Int, last: Int): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = videoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            fun csd(name: String) = format.getByteBuffer(name)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                ?: throw IOException("No $name in ${source.name}")
            val writer = com.xreal.stereoview.record.MkvWriter(
                target, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), csd("csd-0"), csd("csd-1"),
            )
            var buffer = ByteBuffer.allocate(8 shl 20)
            var index = 0
            try {
                while (index <= last) {
                    if (index >= keyframe) {
                        if (extractor.sampleSize > buffer.capacity()) buffer = ByteBuffer.allocate(extractor.sampleSize.toInt())
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) break
                        val bytes = ByteArray(n).also { buffer.position(0); buffer.get(it, 0, n) }
                        writer.writeFrame(extractor.sampleTime, bytes, 0, n, extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    }
                    index++
                    if (!extractor.advance()) break
                }
            } finally {
                writer.close()
            }
        } finally {
            extractor.release()
        }
        return keyframe
    }

    /**
     * Matroska recordings are read with [MkvReader] (no Android extractor), so files without a
     * seek index or cut off by a crash are trimmed the same on every device.
     */
    private fun cutMkv(source: File, target: File, first: Int, last: Int): Int =
        MkvReader.open(source).use { mkv ->
            if (mkv.frames.isEmpty()) throw IOException("${source.name} has no frames")
            val keyframe = mkv.keyframeAtOrBefore(first)
            val writer = com.xreal.stereoview.record.MkvWriter(target, mkv.width, mkv.height, mkv.sps, mkv.pps)
            try {
                for (i in keyframe..minOf(last, mkv.frames.size - 1)) {
                    val data = mkv.annexB(i)
                    writer.writeFrame(mkv.frames[i].timeMs * 1000, data, 0, data.size, mkv.frames[i].keyframe)
                }
            } finally {
                writer.close()
            }
            keyframe
        }

    private fun keyframeAtOrBefore(source: File, frame: Int): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            extractor.selectTrack(videoTrack(extractor))
            var index = 0
            var keyframe = 0
            while (index <= frame) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keyframe = index
                index++
                if (!extractor.advance()) break
            }
            return keyframe
        } finally {
            extractor.release()
        }
    }

    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IOException("No video track")
}
