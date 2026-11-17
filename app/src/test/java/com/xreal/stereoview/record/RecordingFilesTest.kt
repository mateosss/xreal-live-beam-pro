package com.xreal.stereoview.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class RecordingFilesTest {
    private fun sample(root: File): File {
        val dir = File(root, "2026-01-01_00-00-00")
        File(dir, "mav0/cam0/data").mkdirs()
        File(dir, "mav0/cam0/data.csv").writeText("#timestamp [ns],filename\n1,1.png\n")
        File(dir, "mav0/cam0/data/1.png").writeBytes(Random(1).nextBytes(100_000))
        File(dir, "metadata.json").writeText("{\"source\":\"TEST\"}")
        return dir
    }

    private fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }

    @Test
    fun zipThenUnzipRestoresTheFolderAndRemovesTheOtherForm() {
        val root = Files.createTempDirectory("rec").toFile()
        val dir = sample(root)
        val before = snapshot(dir)
        val zip = RecordingFiles.zip(dir)
        assertFalse(dir.exists())
        assertTrue(zip.isFile)
        val back = RecordingFiles.unzip(zip)
        assertFalse(zip.exists())
        assertEquals(dir, back)
        val after = snapshot(back)
        assertEquals(before.keys, after.keys)
        before.forEach { (k, v) -> assertArrayEquals(k, v, after[k]) }
        root.deleteRecursively()
    }

    @Test
    fun copyAddsNoMediaToFolders() {
        val root = Files.createTempDirectory("rec").toFile()
        val dir = sample(root)
        val copy = RecordingFiles.copy(dir, File(root, "Download"))
        assertTrue(File(copy, RecordingFiles.NO_MEDIA).exists())
        assertEquals(RecordingFiles.size(dir), RecordingFiles.size(copy))
        assertTrue(dir.exists())
        root.deleteRecursively()
    }
}
