package com.xreal.stereoview.record

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A text file written line by line without ever blocking the caller on storage: [line] only
 * appends to a memory buffer, and a shared background thread ([DiskWriter]) writes buffered
 * text to the file every 0.1 s and fsyncs it every 2 s, so a crash (of the app or the phone)
 * loses at most the last seconds. [close] writes the rest and closes the file.
 * Thread-safe.
 */
class LineWriter(file: File, header: String? = null) {
    private val out = FileOutputStream(file)
    private var pending = StringBuilder(1 shl 14)
    private var spare = StringBuilder(1 shl 14)
    private var closed = false
    private val ioLock = Any()

    var lines = 0L
        private set

    /** Characters waiting to be written; also the largest backlog seen. */
    @Volatile
    var backlogChars = 0
        private set
    @Volatile
    var peakBacklogChars = 0
        private set

    @Volatile
    var error: IOException? = null
        private set

    init {
        header?.let(::line)
        DiskWriter.register(this)
    }

    fun line(text: CharSequence) {
        synchronized(this) {
            if (closed) return
            pending.append(text).append('\n')
            lines++
            backlogChars = pending.length
            if (pending.length > peakBacklogChars) peakBacklogChars = pending.length
        }
    }

    /**
     * Appends [text] and writes it (with anything buffered) to the file before returning. For
     * background threads only: video recordings write each data.csv row this way just before
     * its frame, so the rows never fall behind the frames in the file.
     */
    fun lineNow(text: CharSequence) {
        line(text)
        drain()
    }

    /** Writes what is buffered (background thread, or [close]). */
    internal fun drain() {
        synchronized(ioLock) {
            val chunk = synchronized(this) {
                if (pending.isEmpty()) return
                val full = pending
                pending = spare
                spare = full
                backlogChars = 0
                full
            }
            try {
                if (error == null) out.write(chunk.toString().toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w("LineWriter", "Write failed", e)
                error = e
            }
            chunk.setLength(0)
        }
    }

    /** Asks the kernel to put written data on storage, so it survives a phone crash too. */
    internal fun sync() {
        synchronized(ioLock) {
            if (closed) return
            try {
                out.fd.sync()
            } catch (_: IOException) {
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        DiskWriter.unregister(this)
        drain()
        synchronized(ioLock) {
            try {
                out.fd.sync()
            } catch (_: IOException) {
            }
            out.close()
        }
    }
}

/**
 * The thread that writes every open [LineWriter]'s buffered lines to storage, so camera, IMU
 * and GL threads never wait for it. Runs only while a writer is open.
 */
object DiskWriter {
    private const val PERIOD_MS = 100L
    /** fsync every 2 s: lines reach the file within 0.1 s (safe from an app crash) and storage within ~2 s. */
    private const val SYNC_EVERY = 20
    private var ticks = 0
    private val writers = LinkedHashSet<LineWriter>()
    private var thread: Thread? = null

    @Synchronized
    fun register(w: LineWriter) {
        writers += w
        if (thread == null) {
            thread = Thread(::run, "DiskWriter").apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun unregister(w: LineWriter) {
        writers -= w
    }

    private fun run() {
        while (true) {
            val snapshot = synchronized(this) {
                if (writers.isEmpty()) {
                    thread = null
                    return
                }
                writers.toList()
            }
            snapshot.forEach { it.drain() }
            if (++ticks % SYNC_EVERY == 0) snapshot.forEach { it.sync() }
            Thread.sleep(PERIOD_MS)
        }
    }
}
