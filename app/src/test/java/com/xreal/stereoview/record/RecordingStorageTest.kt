package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

class RecordingStorageTest {
    private val now = Calendar.getInstance(TimeZone.getDefault()).apply { set(2026, Calendar.MARCH, 4, 5, 6, 7) }.time

    @Test
    fun namesTheDirectoryAfterTheStartTime() {
        val root = Files.createTempDirectory("rec").toFile()
        val dir = RecordingStorage.createDir(root, now)
        assertEquals("2026-03-04_05-06-07", dir.name)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun recordingsStartedWithinOneSecondGetTheirOwnDirectories() {
        val root = Files.createTempDirectory("rec").toFile()
        val names = List(3) { RecordingStorage.createDir(root, now).name }
        assertEquals(listOf("2026-03-04_05-06-07", "2026-03-04_05-06-07_2", "2026-03-04_05-06-07_3"), names)
    }

    @Test
    fun createsTheRootIfMissing() {
        val root = Files.createTempDirectory("rec").toFile().resolve("recordings")
        assertTrue(RecordingStorage.createDir(root, now).isDirectory)
    }
}
