package com.xreal.stereoview.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hardware video encoder fed through an input [Surface] and muxed into an MP4 file. Each frame's
 * presentation time is the camera timestamp set with eglPresentationTimeANDROID, so the file's
 * timestamps are the sensor's (in microseconds, relative to the first frame in most players).
 * No B-frames: decode order equals capture order, so frame i matches row i of frames.csv.
 */
class VideoEncoder(
    file: File,
    mime: String,
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
) {
    val inputSurface: Surface
    val codecName: String

    private val thread = HandlerThread("VideoEncoder").apply { start() }
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val done = CountDownLatch(1)
    private var track = -1

    /** Samples written to the file. Only read after [finish]. */
    @Volatile
    var samples = 0
        private set

    @Volatile
    var error: String? = null
        private set

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        }
        // The lookup must not include a frame rate (or other tuning keys), or it finds nothing.
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createVideoFormat(mime, width, height))
            ?: throw IllegalArgumentException("No $mime encoder for ${width}×$height")
        codecName = name
        codec = MediaCodec.createByCodecName(name)
        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                track = muxer.addTrack(format)
                muxer.start()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !isConfig && track >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        samples++
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error", e)
                error = e.diagnosticInfo
                done.countDown()
            }
        }, Handler(thread.looper))
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            thread.quitSafely()
            throw IllegalArgumentException("$name can't encode ${width}×$height at $fps fps: ${e.message}", e)
        }
    }

    /** Ends the stream and closes the file. Call only after the input surface's EGL surface is gone. */
    fun finish() {
        try {
            codec.signalEndOfInputStream()
            if (!done.await(5, TimeUnit.SECONDS)) error = error ?: "timed out waiting for the last frames"
        } catch (e: IllegalStateException) {
            error = error ?: e.toString()
        }
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "codec.stop", e)
        }
        codec.release()
        inputSurface.release()
        try {
            if (track >= 0) muxer.stop()
        } catch (e: IllegalStateException) {
            error = error ?: "muxer: $e"
        }
        muxer.release()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
