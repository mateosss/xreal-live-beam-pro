package com.xreal.stereoview.record

import android.graphics.Bitmap
import android.util.Log
import com.xreal.stereoview.util.PngGray
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compresses RGBA frames to `<dir>/<timestamp_ns>.jpg`, or with [png] to 8-bit grayscale
 * `<dir>/<timestamp_ns>.png` (as EuRoC stores images), on a small thread pool. Frames are copied
 * into a pool of buffers that grows up to [memoryBudget] bytes, so a few seconds of slow
 * storage or compression are absorbed; only when that backlog is full is a frame dropped
 * (and counted) instead of stalling the camera.
 */
class ImageWriter(
    private val dir: File,
    val width: Int,
    val height: Int,
    val png: Boolean,
    memoryBudget: Long = 160L shl 20,
    private val quality: Int = 95,
) {
    val extension: String get() = if (png) "png" else "jpg"
    private val frameBytes = width * height * 4
    /** Frames that can wait at once: at least 8, about [memoryBudget] bytes in all. */
    val capacity = (memoryBudget / frameBytes).toInt().coerceIn(8, 240)
    private val free = ArrayBlockingQueue<ByteBuffer>(capacity)
    private var allocated = 0
    private val executor = Executors.newFixedThreadPool(THREADS) { r ->
        Thread(r, "ImageWriter").apply { priority = Thread.NORM_PRIORITY }
    }
    private val bitmaps = ThreadLocal.withInitial { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val grays = ThreadLocal.withInitial { ByteArray(width * height) }

    val written = AtomicInteger()
    val dropped = AtomicInteger()
    val failed = AtomicInteger()
    private val queued = AtomicInteger()

    /** Frames waiting to be written now, and the most seen at once. */
    val backlog: Int get() = queued.get()
    @Volatile
    var peakBacklog = 0
        private set

    init {
        dir.mkdirs()
        repeat(8) { free.add(newBuffer()) }
    }

    private fun newBuffer(): ByteBuffer {
        allocated++
        return ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder())
    }

    /**
     * Fills a free buffer with [read] (top row first, RGBA) and queues it. Returns false if the
     * frame was dropped.
     */
    fun submit(timestampNs: Long, read: (ByteBuffer) -> Unit): Boolean {
        val buffer = free.poll() ?: (if (allocated < capacity) runCatching { newBuffer() }.getOrNull() else null) ?: run {
            dropped.incrementAndGet()
            return false
        }
        buffer.position(0)
        read(buffer)
        val n = queued.incrementAndGet()
        if (n > peakBacklog) peakBacklog = n
        executor.execute {
            try {
                buffer.position(0)
                val file = File(dir, "$timestampNs.$extension")
                if (png) {
                    val gray = grays.get()!!
                    for (i in gray.indices) {
                        val r = buffer.get(4 * i).toInt() and 0xff
                        val g = buffer.get(4 * i + 1).toInt() and 0xff
                        val b = buffer.get(4 * i + 2).toInt() and 0xff
                        gray[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte() // BT.601 luma
                    }
                    PngGray.write(file, gray, width, height)
                } else {
                    val bitmap = bitmaps.get()!!
                    bitmap.copyPixelsFromBuffer(buffer)
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                }
                written.incrementAndGet()
            } catch (e: Exception) {
                Log.w("ImageWriter", "Writing $timestampNs failed", e)
                failed.incrementAndGet()
            } finally {
                queued.decrementAndGet()
                free.add(buffer)
            }
        }
        return true
    }

    /** Waits for queued frames to be written. */
    fun finish() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.MINUTES)
    }

    private companion object {
        const val THREADS = 3
    }
}
