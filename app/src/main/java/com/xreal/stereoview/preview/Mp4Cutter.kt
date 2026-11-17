package com.xreal.stereoview.preview

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.xreal.stereoview.record.DatasetTrimmer
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
        val keyframe = keyframeAtOrBefore(source, first)
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
