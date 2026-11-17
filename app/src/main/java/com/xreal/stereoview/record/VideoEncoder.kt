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
 *
 * Encoded samples are copied out of the codec at once and written to the file by a separate
 * thread, so slow storage never holds up the encoder (and through it the GL thread).
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
    private val writer = MuxerWriter()

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
                writer.post { track = muxer.addTrack(format); muxer.start() }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !isConfig) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val copy = java.nio.ByteBuffer.allocateDirect(info.size).put(buffer)
                        copy.flip()
                        val meta = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        writer.post(info.size) {
                            if (track >= 0) {
                                muxer.writeSampleData(track, copy, meta)
                                samples++
                            }
                        }
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
            writer.finish()
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
        writer.finish()
        try {
            if (track >= 0) muxer.stop()
        } catch (e: IllegalStateException) {
            error = error ?: "muxer: $e"
        }
        muxer.release()
        thread.quitSafely()
    }

    /** Encoded bytes waiting for the file, and the most seen at once. */
    val backlogBytes: Long get() = writer.bytes.get()
    val peakBacklogBytes: Long get() = writer.peak

    /** Runs muxer calls in order on its own thread; waits only if [MAX_BACKLOG] bytes are queued. */
    private class MuxerWriter {
        val bytes = java.util.concurrent.atomic.AtomicLong()
        @Volatile
        var peak = 0L
        private val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, () -> Unit>>()
        private val space = java.util.concurrent.Semaphore(MAX_BACKLOG)
        private val thread = Thread({
            while (true) {
                val (size, task) = queue.take()
                if (size < 0) break
                try {
                    task()
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer write failed", e)
                } finally {
                    bytes.addAndGet(-size.toLong())
                    space.release(size)
                }
            }
        }, "VideoMuxer").apply { start() }

        fun post(size: Int = 0, task: () -> Unit) {
            val n = size.coerceAtMost(MAX_BACKLOG)
            space.acquireUninterruptibly(n)
            val total = bytes.addAndGet(n.toLong())
            if (total > peak) peak = total
            queue.put(n to task)
        }

        fun finish() {
            queue.put(-1 to {})
            thread.join(60_000)
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        /** Encoded video that may wait for storage: tens of seconds at recording bitrates. */
        const val MAX_BACKLOG = 128 shl 20
    }
}
