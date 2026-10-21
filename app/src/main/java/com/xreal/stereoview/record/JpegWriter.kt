package com.xreal.stereoview.record

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compresses RGBA frames to `<dir>/<timestamp_ns>.jpg` on a small thread pool. Frames are read
 * into a fixed pool of buffers; when all are busy (the encoder can't keep up) the frame is
 * dropped and counted instead of stalling the camera.
 */
class JpegWriter(private val dir: File, val width: Int, val height: Int, private val quality: Int = 95) {
    private val free = ArrayBlockingQueue<ByteBuffer>(BUFFERS)
    private val executor = Executors.newFixedThreadPool(THREADS)
    private val bitmaps = ThreadLocal.withInitial { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }

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
                val bitmap = bitmaps.get()!!
                bitmap.copyPixelsFromBuffer(buffer)
                FileOutputStream(File(dir, "$timestampNs.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                written.incrementAndGet()
            } catch (e: Exception) {
                Log.w("JpegWriter", "Writing $timestampNs failed", e)
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
