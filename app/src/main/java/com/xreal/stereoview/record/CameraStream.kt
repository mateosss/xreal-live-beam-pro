package com.xreal.stereoview.record

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One camera of a dataset recorded from frames the app receives (glasses, OAK): mav0/<name>/
 * with data.csv, frames.csv (one row per stored frame, [framesHeader]) and either images
 * (data/<timestamp>.<extension>, written by the caller, then [imageStored]) or a video
 * ([video], data.mkv: data.csv rows are written as frames reach the file, so row i is frame i
 * even after a crash). Keeps the statistics for metadata.json.
 */
class CameraStream(val name: String, val dir: File, val extension: String, framesHeader: String) {
    private val data = LineWriter(File(dir, "data.csv"), Euroc.CAMERA_HEADER)
    private val frames = LineWriter(File(dir, "frames.csv"), framesHeader)

    /** Video recordings: the camera's encoder, set right after construction. */
    var video: VideoEncoder? = null
    private val pts = VideoEncoder.Companion.PtsMap()

    var stored = 0L
        private set
    private var firstNs = Long.MAX_VALUE
    private var lastNs = Long.MIN_VALUE

    /** Largest step between consecutive timestamps of data.csv, measured once it is sorted ([finish]). */
    private var maxGapNs = 0L

    init {
        if (extension != GrayImageFormat.H264.extension) File(dir, "data").mkdirs()
    }

    fun imageFile(timestampNs: Long) = File(dir, "data/$timestampNs.$extension")

    /**
     * Video recordings: hands frame [timestampNs] to [video] with [submit] and logs its
     * frames.csv row. Returns false if the encoder's backlog was full and the frame dropped.
     */
    fun submitVideo(timestampNs: Long, framesRow: String, submit: (VideoEncoder) -> Boolean): Boolean {
        val v = video ?: return false
        pts.put(timestampNs, timestampNs)
        if (!submit(v)) return false
        frames.line(framesRow)
        return true
    }

    /** Image recordings, any thread: the image of [timestampNs] was written. */
    @Synchronized
    fun imageStored(timestampNs: Long, framesRow: String) {
        count(timestampNs)
        data.line("$timestampNs,$timestampNs.$extension")
        frames.line(framesRow)
    }

    /** Encoder thread: a frame reached the video file. */
    @Synchronized
    fun videoFrameWritten(ptsUs: Long) {
        val t = pts.take(ptsUs)
        count(t)
        // EuRoC names a file per frame; tools/to_euroc.py extracts frame i of data.mkv as row i's PNG.
        data.lineNow("$t,$t.png")
    }

    // Frames are stored by several threads (or reach the file) out of order: first and last are the extremes.
    private fun count(t: Long) {
        firstNs = minOf(firstNs, t)
        lastNs = maxOf(lastNs, t)
        stored++
    }

    /** A failed write of data.csv or frames.csv, if any. */
    val writeError: Throwable? get() = data.error ?: frames.error

    /**
     * Finishes the video (if any) and closes the files; image rows, written by several
     * threads, are sorted by timestamp. Returns the encoder's error, if it had one.
     */
    fun finish(): String? {
        val encoderError = video?.let {
            it.finish()
            it.error
        }
        data.close()
        frames.close()
        Euroc.sortByTimestamp(File(dir, "data.csv"))
        Euroc.sortByTimestamp(File(dir, "frames.csv"))
        maxGapNs = runCatching { largestGap(File(dir, "data.csv")) }.getOrDefault(0L)
        return encoderError
    }

    /** Statistics for metadata.json (after [finish]). */
    @Synchronized
    fun statsJson(): JSONObject = JSONObject().apply {
        put("frames", stored)
        put("first_timestamp_ns", if (stored > 0) firstNs else 0L)
        put("last_timestamp_ns", if (stored > 0) lastNs else 0L)
        if (stored > 1) put("mean_fps", Euroc.meanRateHz(stored, firstNs, lastNs))
        put("max_gap_ms", maxGapNs / 1e6)
        video?.let { v ->
            put("encoder_frames_dropped", v.dropped.get())
            put("encoder_backlog_peak_frames", v.peakPending)
            put("encoder_backlog_capacity_frames", v.maxPending)
        }
    }

    /** What metadata.json's camera entry adds about the encoder (after [finish]). */
    fun encoderJson(into: JSONObject) {
        val v = video ?: return
        into.put("encoder_settings", v.applied)
        if (v.notWritten > 0) into.put("video_frames_not_written", v.notWritten)
    }
}

/** Largest step between consecutive timestamps (first column) of a sorted CSV with a header. */
internal fun largestGap(csv: File): Long {
    var previous = Long.MIN_VALUE
    var gap = 0L
    csv.useLines { lines ->
        for (line in lines.drop(1)) {
            val t = line.substringBefore(',').toLongOrNull() ?: continue
            if (previous != Long.MIN_VALUE) gap = maxOf(gap, t - previous)
            previous = t
        }
    }
    return gap
}

/**
 * Writes frames to files on [threads] threads, with up to [capacity] frames waiting: a few
 * seconds of slow storage are absorbed; beyond that frames are dropped (and counted) instead
 * of stalling the thread that delivers them.
 */
class FrameWriterPool(threads: Int, val capacity: Int, name: String) {
    private val executor = ThreadPoolExecutor(threads, threads, 1, TimeUnit.SECONDS, ArrayBlockingQueue(capacity)) { r -> Thread(r, name) }
    private val droppedCount = AtomicInteger()
    private val failedCount = AtomicInteger()

    @Volatile var peak = 0
        private set

    val dropped: Int get() = droppedCount.get()
    val failed: Int get() = failedCount.get()
    val waiting: Int get() = executor.queue.size

    /** Queues [write]; if it throws, the frame counts as failed. Returns false if the queue was full (dropped). */
    fun submit(write: () -> Unit): Boolean {
        try {
            executor.execute {
                try {
                    write()
                } catch (e: Exception) {
                    failedCount.incrementAndGet()
                    runCatching { Log.w("FrameWriterPool", "Writing a frame failed", e) }
                }
            }
        } catch (e: RejectedExecutionException) {
            droppedCount.incrementAndGet()
            return false
        }
        executor.queue.size.let { if (it > peak) peak = it }
        return true
    }

    /** A frame dropped elsewhere (e.g. a full encoder backlog), counted with these. */
    fun countDropped() {
        droppedCount.incrementAndGet()
    }

    val backlog: String get() = "write queue $waiting/$capacity images (peak $peak), dropped $dropped"

    /** Waits for the queued frames to be written. */
    fun finish() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.MINUTES)
    }

    /** Statistics for metadata.json; [droppedKey] keeps each recording kind's earlier name. */
    fun statsJson(droppedKey: String): JSONObject = JSONObject()
        .put(droppedKey, dropped).put("frames_failed", failed)
        .put("write_queue_capacity_frames", capacity).put("write_queue_peak_frames", peak)
}
