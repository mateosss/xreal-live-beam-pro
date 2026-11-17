package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

class LineWriterTest {
    @Test
    fun writesEveryLineFromSeveralThreadsInOrderPerThread() {
        val file = File.createTempFile("lines", ".csv").apply { deleteOnExit() }
        val w = LineWriter(file, "header")
        val threads = (0 until 4).map { t -> thread { repeat(5000) { i -> w.line("$t,$i") } } }
        threads.forEach { it.join() }
        w.close()
        val lines = file.readLines()
        assertEquals("header", lines[0])
        assertEquals(1 + 4 * 5000, lines.size)
        for (t in 0 until 4) {
            val mine = lines.drop(1).filter { it.startsWith("$t,") }.map { it.substringAfter(',').toInt() }
            assertEquals((0 until 5000).toList(), mine)
        }
        w.line("after close") // ignored
        assertEquals(1 + 4 * 5000, file.readLines().size)
    }
}
