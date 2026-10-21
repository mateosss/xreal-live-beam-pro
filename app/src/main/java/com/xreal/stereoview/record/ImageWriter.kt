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
 * `<dir>/<timestamp_ns>.png` (as EuRoC stores images), on a small thread pool. Frames are read
 * into a fixed pool of buffers; when all are busy (the encoder can't keep up) the frame is
 * dropped and counted instead of stalling the camera.
 */
class ImageWriter(private val dir: File, val width: Int, val height: Int, val png: Boolean, private val quality: Int = 95) {
    val extension: String get() = if (png) "png" else "jpg"
    private val free = ArrayBlockingQueue<ByteBuffer>(BUFFERS)
    private val executor = Executors.newFixedThreadPool(THREADS)
    private val bitmaps = ThreadLocal.withInitial { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val grays = ThreadLocal.withInitial { ByteArray(width * height) }

    val written = AtomicInteger()
    val dropped = AtomicInteger()
    val failed = AtomicInteger()

    init {
        dir.mkdirs()
        repeat(BUFFERS) { free.add(ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())) }
    }

    /**
     * Fills a free buffer with [read] (top row first, RGBA) and queues it. Returns false if the
     * frame was dropped.
     */
    fun submit(timestampNs: Long, read: (ByteBuffer) -> Unit): Boolean {
        val buffer = free.poll() ?: run {
            dropped.incrementAndGet()
            return false
        }
        buffer.position(0)
        read(buffer)
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
                free.add(buffer)
            }
        }
        return true
    }

    /** Waits for queued frames to be written. */
    fun finish() {
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }

    private companion object {
        const val BUFFERS = 8
        const val THREADS = 3
    }
}
