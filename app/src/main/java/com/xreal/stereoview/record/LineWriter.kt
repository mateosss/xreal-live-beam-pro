package com.xreal.stereoview.record

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/** Thread-safe buffered text file, written one line at a time (CSV rows, JSON lines). */
class LineWriter(file: File, header: String? = null) {
    private val writer = BufferedWriter(FileWriter(file), 1 shl 16)
    private var closed = false

    var lines = 0L
        private set

    init {
        header?.let(::line)
    }

    @Synchronized
    fun line(text: CharSequence) {
        if (closed) return
        writer.append(text).append('\n')
        lines++
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        writer.close()
    }
}
