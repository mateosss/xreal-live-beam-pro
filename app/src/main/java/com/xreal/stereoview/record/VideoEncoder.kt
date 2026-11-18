package com.xreal.stereoview.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** H.264/H.265 rate control and stream structure for recordings. */
data class EncoderOptions(
    val bitrateBps: Int,
    val mode: BitrateMode = BitrateMode.CBR,
    /** Seconds between keyframes: shorter = faster seeking and less lost on corruption, larger files. */
    val keyframeIntervalS: Float = 1f,
    val profile: H264Profile = H264Profile.HIGH,
)

enum class BitrateMode(val label: String, val android: Int) {
    CBR("Constant bitrate (CBR): predictable size, steady quality loss in busy scenes", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
    VBR("Variable bitrate (VBR): bitrate is the average; better quality per byte", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
    CQ("Constant quality (CQ): fixed quality, size varies a lot (not all encoders)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
}

enum class H264Profile(val label: String, val android: Int) {
    BASELINE("Baseline (most compatible)", MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline),
    MAIN("Main", MediaCodecInfo.CodecProfileLevel.AVCProfileMain),
    HIGH("High (best compression)", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
}

/**
 * Hardware video encoder for recordings. Input is either a [Surface] (frames rendered by GL,
 * stamped with eglPresentationTimeANDROID) or 8-bit grayscale frames ([submitGray], encoded as
 * YUV with neutral chroma). H.264 goes into a crash-safe Matroska file ([MkvWriter]); H.265 into
 * MP4 (readable only once finished). No B-frames: frame i of the file is the i-th frame written.
 *
 * Encoded samples are copied out of the codec at once and written by a separate thread, so slow
 * storage never holds up the encoder. [onWritten] gets each frame's presentation time (µs) on
 * that thread just before the frame is written: recordings write its data.csv row there
 * (straight to the file), so rows match the frames in the file; after a crash there may be one
 * row more than frames, never fewer.
 */
class VideoEncoder(
    file: File,
    mime: String,
    val width: Int,
    val height: Int,
    fps: Int,
    options: EncoderOptions,
    private val grayInput: Boolean = false,
    private val onWritten: (ptsUs: Long) -> Unit = {},
) {
    /** The input surface (surface input only). */
    val inputSurface: Surface?
    val codecName: String
    /** What was actually configured (encoders ignore unsupported modes and profiles). */
    val applied = JSONObject()

    private val thread = HandlerThread("VideoEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    private val sink: Sink
    private val done = CountDownLatch(1)
    private val writer = SinkWriter()

    @Volatile
    var samples = 0
    /** Encoded frames that could not go into the file (container not started); they have no data.csv row. */
    @Volatile var notWritten = 0
        private set

    @Volatile
    var error: String? = null
        private set

    private lateinit var callback: MediaCodec.Callback

    /** Gray frames dropped because the encoder fell behind. */
    val dropped = AtomicInteger()

    // Gray input state (declared before init: the codec calls back as soon as it starts).
    private class GrayFrame(val ptsUs: Long, val pixels: ByteArray)
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<GrayFrame>()
    private var lastPtsUs = Long.MIN_VALUE
    /** End of stream requested while no input buffer was free: sent with the next one. */
    @Volatile
    private var pendingEos = false

    init {
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createVideoFormat(mime, width, height))
            ?: throw IllegalArgumentException("No $mime encoder for ${width}×$height")
        codecName = name
        codec = MediaCodec.createByCodecName(name)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (grayInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, options.keyframeIntervalS)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            // SPS/PPS before every keyframe: each GOP decodes on its own.
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        configureRateAndProfile(format, mime, options)
        applied.put("encoder", name).put("container", if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) "matroska (crash-safe)" else "mp4")
            .put("keyframe_interval_s", options.keyframeIntervalS.toDouble()).put("fps", fps)
        sink = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) MkvSink(file, width, height) else Mp4Sink(file)
        callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                freeInputs.addLast(index)
                feed()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                writer.post { sink.start(format) }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val copy = ByteArray(info.size).also { buffer.get(it) }
                        val pts = info.presentationTimeUs
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (isConfig) {
                            // Some encoders give SPS/PPS only here, not (or not fully) in the output format.
                            writer.post { sink.config(copy) }
                        } else {
                            writer.post(info.size) {
                                // Only frames that go into the file get a data.csv row; the row first, so a
                                // crash between the two leaves one extra row, never a frame without one.
                                if (sink.ready(copy, key)) {
                                    onWritten(pts)
                                    sink.write(copy, pts, key)
                                    samples++
                                } else {
                                    notWritten++
                                    if (error == null) error = sink.notReadyReason
                                }
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
        }
        codec.setCallback(callback, handler)
        try {
            configureWithFallbacks(format)
            inputSurface = if (grayInput) null else codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            codec.release()
            writer.finish()
            thread.quitSafely()
            throw IllegalArgumentException("$name can't encode ${width}×$height at $fps fps: ${e.message}", e)
        }
    }

    /**
     * Configures the codec; encoders that reject an optional setting are retried without it,
     * in turn: SPS/PPS repetition, profile and level, rate control mode. [applied] notes what
     * was dropped.
     */
    private fun configureWithFallbacks(format: MediaFormat) {
        val optional = listOf(
            listOf(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES),
            listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL),
            listOf(MediaFormat.KEY_BITRATE_MODE, MediaFormat.KEY_QUALITY),
        )
        var lastError: Exception? = null
        val dropped = ArrayList<String>()
        for (step in 0..optional.size) {
            if (step > 0) {
                val keys = optional[step - 1]
                if (keys.none { format.containsKey(it) }) continue
                keys.forEach { format.removeKey(it) }
                dropped += keys.first()
                // Without a mode the encoder needs a bitrate (CQ had none).
                if (!format.containsKey(MediaFormat.KEY_BIT_RATE)) format.setInteger(MediaFormat.KEY_BIT_RATE, applied.optInt("bitrate_bps", 8_000_000))
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (dropped.isNotEmpty()) {
                    applied.put("rejected_by_encoder", org.json.JSONArray(dropped))
                    if (MediaFormat.KEY_BITRATE_MODE in dropped) applied.put("bitrate_mode", "encoder default")
                    if (MediaFormat.KEY_PROFILE in dropped) applied.put("profile", "encoder default")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "configure failed${if (dropped.isEmpty()) "" else " without $dropped"}: $e")
                lastError = e
                runCatching {
                    codec.reset()
                    codec.setCallback(callback, handler)
                }
            }
        }
        throw lastError ?: IllegalStateException("configure failed")
    }

    /** Rate control and profile, each only if this encoder supports it (else its default). */
    private fun configureRateAndProfile(format: MediaFormat, mime: String, o: EncoderOptions) {
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == codecName }.getCapabilitiesForType(mime)
        }.getOrNull()
        val enc = caps?.encoderCapabilities
        val mode = o.mode.takeIf { enc?.isBitrateModeSupported(it.android) != false }
            ?: BitrateMode.VBR.takeIf { enc?.isBitrateModeSupported(it.android) == true }
        if (mode != null) format.setInteger(MediaFormat.KEY_BITRATE_MODE, mode.android)
        if (mode == BitrateMode.CQ) {
            val q = enc?.qualityRange
            format.setInteger(MediaFormat.KEY_QUALITY, q?.upper?.let { (q.lower + (it - q.lower) * 0.8).toInt() } ?: 80)
        } else {
            format.setInteger(MediaFormat.KEY_BIT_RATE, o.bitrateBps)
        }
        applied.put("bitrate_mode", mode?.name ?: "encoder default").put("bitrate_bps", o.bitrateBps)
        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
            val level = caps?.profileLevels?.filter { it.profile == o.profile.android }?.maxOfOrNull { it.level }
            if (level != null) {
                format.setInteger(MediaFormat.KEY_PROFILE, o.profile.android)
                format.setInteger(MediaFormat.KEY_LEVEL, level)
                applied.put("profile", o.profile.name)
            } else {
                applied.put("profile", "encoder default (${o.profile.name} unsupported)")
            }
        }
    }

    // region Gray input

    private var loggedLayout = false


    /**
     * Queues an 8-bit [width]×[height] image at [timestampNs] (increasing). Returns false if it
     * was dropped (encoder behind, or not after the previous frame). The array must not change.
     */
    fun submitGray(timestampNs: Long, pixels: ByteArray): Boolean {
        val pts = timestampNs / 1000
        synchronized(pending) {
            if (pts <= lastPtsUs || pending.size >= MAX_PENDING) {
                dropped.incrementAndGet()
                return false
            }
            lastPtsUs = pts
            pending.addLast(GrayFrame(pts, pixels))
        }
        handler.post(::feed)
        return true
    }

    /** Codec thread: fills free input buffers with queued gray frames. */
    private fun feed() {
        while (freeInputs.isNotEmpty()) {
            val frame = synchronized(pending) { pending.pollFirst() } ?: run {
                if (pendingEos) {
                    pendingEos = false
                    codec.queueInputBuffer(freeInputs.removeFirst(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                return
            }
            val index = freeInputs.removeFirst()
            try {
                val image = codec.getInputImage(index) ?: continue
                if (!loggedLayout) {
                    loggedLayout = true
                    val fmt = runCatching { codec.inputFormat }.getOrNull()
                    val layout = org.json.JSONObject()
                        .put("image", "${image.width}x${image.height} crop ${image.cropRect.toShortString()}")
                        .put("planes", org.json.JSONArray(image.planes.map { pl -> "rowStride ${pl.rowStride} pixelStride ${pl.pixelStride} bytes ${pl.buffer.remaining()}" }))
                    fmt?.let { f ->
                        for (k in listOf(MediaFormat.KEY_STRIDE, MediaFormat.KEY_SLICE_HEIGHT, MediaFormat.KEY_COLOR_FORMAT)) {
                            if (f.containsKey(k)) layout.put(k, f.getInteger(k))
                        }
                    }
                    applied.put("gray_input_layout", layout)
                    Log.i(TAG, "Gray input layout: $layout")
                }
                val yStride = image.planes[0].rowStride
                val yPixel = image.planes[0].pixelStride
                // Write the raw input buffer rather than the Image's planes: luma rows at the
                // Image's stride, then every byte after them 128 (all chroma, any padding),
                // and hand over the whole buffer. Encoders may read chroma beyond what the
                // Image's planes expose, or past width×height×1.5 bytes; filling the planes
                // and declaring that size left green strips on the right of glasses frames.
                val raw = if (yPixel == 1) codec.getInputBuffer(index) else null // invalidates image
                if (raw != null && raw.capacity() >= yStride * (height - 1) + width) {
                    raw.clear()
                    for (r in 0 until height) {
                        raw.position(r * yStride)
                        raw.put(frame.pixels, r * width, width)
                    }
                    fillNeutral(raw)
                    codec.queueInputBuffer(index, 0, raw.capacity(), frame.ptsUs, 0)
                } else {
                    val buffer = image.planes[0].buffer
                    for (r in 0 until height) {
                        buffer.position(r * yStride)
                        buffer.put(frame.pixels, r * width, width)
                    }
                    for (p in 1..2) { image.planes[p].buffer.position(0); fillNeutral(image.planes[p].buffer) }
                    codec.queueInputBuffer(index, 0, width * height * 3 / 2, frame.ptsUs, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Feeding a frame failed", e)
                error = error ?: e.toString()
            }
        }
    }

    /** Sets every byte from [b]'s position to its limit to 128 (neutral chroma). */
    private fun fillNeutral(b: ByteBuffer) {
        while (b.hasRemaining()) b.put(NEUTRAL, 0, minOf(NEUTRAL.size, b.remaining()))
    }

    // endregion

    /** Ends the stream and closes the file. Surface input: call only after its EGL surface is gone. */
    fun finish() {
        try {
            if (grayInput) {
                // Let queued frames in, then end the stream with an empty buffer.
                val deadline = SystemClock.elapsedRealtime() + 3000
                while (synchronized(pending) { pending.isNotEmpty() } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                val eos = CountDownLatch(1)
                handler.post {
                    val index = freeInputs.pollFirst()
                    if (index != null) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) else pendingEos = true
                    eos.countDown()
                }
                eos.await(1, TimeUnit.SECONDS)
            } else {
                codec.signalEndOfInputStream()
            }
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
        inputSurface?.release()
        writer.finish()
        try {
            sink.close()
        } catch (e: Exception) {
            error = error ?: "closing the file: $e"
        }
        thread.quitSafely()
    }


    /** Encoded bytes waiting for the file, and the most seen at once. */
    val backlogBytes: Long get() = writer.bytes.get()
    val peakBacklogBytes: Long get() = writer.peak

    // region Containers

    private interface Sink {
        fun start(format: MediaFormat)
        /** A codec-config buffer (SPS/PPS) from the encoder. */
        fun config(data: ByteArray) {}
        /** Whether frame [data] can be written now (starts the container if needed). */
        fun ready(data: ByteArray, keyframe: Boolean): Boolean
        val notReadyReason: String
        fun write(data: ByteArray, ptsUs: Long, keyframe: Boolean)
        fun close()
    }

    /**
     * Matroska: crash-safe, fsync'd every 2 s. The file starts once the SPS and PPS are known:
     * encoders give them in the output format (csd-0 and csd-1, or both in csd-0), in a
     * codec-config buffer, or in front of keyframes; all are accepted.
     */
    private class MkvSink(private val file: File, private val width: Int, private val height: Int) : Sink {
        private var mkv: MkvWriter? = null
        private var lastSync = 0L
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null
        override val notReadyReason = "the encoder gave no SPS/PPS, so frames before the first keyframe carrying them were not written"

        override fun start(format: MediaFormat) {
            for (key in listOf("csd-0", "csd-1")) format.getByteBuffer(key)?.let { learn(bytes(it)) }
        }

        override fun config(data: ByteArray) = learn(data)

        private fun learn(data: ByteArray) {
            for (nal in MkvWriter.nalUnits(data, 0, data.size)) {
                when (nal[0].toInt() and 0x1f) {
                    7 -> if (sps == null) sps = nal
                    8 -> if (pps == null) pps = nal
                }
            }
        }

        override fun ready(data: ByteArray, keyframe: Boolean): Boolean {
            if (mkv != null) return true
            if (!keyframe) return false
            learn(data)
            val s = sps ?: return false
            val p = pps ?: return false
            mkv = MkvWriter(file, width, height, s, p)
            Log.i(TAG, "Writing ${file.name}: SPS ${s.size} B, PPS ${p.size} B")
            return true
        }

        override fun write(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
            val w = mkv ?: return
            w.writeFrame(ptsUs, data, 0, data.size, keyframe)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSync > 2000) {
                lastSync = now
                w.sync()
            }
        }

        override fun close() {
            mkv?.close()
        }

        private fun bytes(b: ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }
    }

    /** MP4 (H.265): standard, but unreadable if the recording doesn't end normally. */
    private class Mp4Sink(file: File) : Sink {
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private val info = MediaCodec.BufferInfo()

        override fun start(format: MediaFormat) {
            track = muxer.addTrack(format)
            muxer.start()
        }

        override val notReadyReason = "the encoder gave no output format, so frames were not written"
        override fun ready(data: ByteArray, keyframe: Boolean) = track >= 0

        override fun write(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
            if (track < 0) return
            info.set(0, data.size, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
        }

        override fun close() {
            try {
                if (track >= 0) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    }

    /** Runs sink calls in order on its own thread; waits only if [MAX_BACKLOG] bytes are queued. */
    private class SinkWriter {
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
                    Log.e(TAG, "Video write failed", e)
                } finally {
                    bytes.addAndGet(-size.toLong())
                    space.release(size)
                }
            }
        }, "VideoWriter").apply { start() }

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

    // endregion

    companion object {
        private const val TAG = "VideoEncoder"
        private val NEUTRAL = ByteArray(8192) { 128.toByte() }
        /** Encoded video that may wait for storage: tens of seconds at recording bitrates. */
        const val MAX_BACKLOG = 128 shl 20
        /** Gray frames waiting for an encoder input buffer (about 1 s at 30 fps). */
        const val MAX_PENDING = 30

        /** Matches recorded presentation times (µs) back to exact ns timestamps. */
        class PtsMap {
            private val map = ConcurrentHashMap<Long, Long>()
            /** The frame presented at [presentationNs] (its pts is that / 1000) has dataset time [datasetNs]. */
            fun put(presentationNs: Long, datasetNs: Long) {
                map[presentationNs / 1000] = datasetNs
            }
            fun take(ptsUs: Long): Long = map.remove(ptsUs) ?: (ptsUs * 1000)
        }

    }
}
