package com.xreal.stereoview.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraStreamTest {
    private fun stream(): CameraStream {
        val dir = Files.createTempDirectory("cam").toFile()
        return CameraStream("cam0", dir, "png", "timestamp_ns,sensor_timestamp_ns")
    }

    private fun rows(f: File) = f.readLines().drop(1)

    @Test
    fun imagesStoredOutOfOrderByManyThreadsEndUpSortedWithStats() {
        val cam = stream()
        val pool = FrameWriterPool(4, 1000, "test")
        val period = 33_333_333L
        // Frame 5 is missing: the largest gap is two periods.
        val times = (0L until 200L).filter { it != 5L }.map { 1_000_000_000L + it * period }.shuffled()
        for (t in times) {
            assertTrue(pool.submit {
                cam.imageFile(t).writeText("x")
                cam.imageStored(t, "$t,${t + 1}")
            })
        }
        pool.finish()
        assertEquals(null, cam.finish())
        val data = rows(File(cam.dir, "data.csv"))
        assertEquals(times.sorted().map { "$it,$it.png" }, data)
        assertEquals(times.sorted().map { "$it,${it + 1}" }, rows(File(cam.dir, "frames.csv")))
        val stats = cam.statsJson()
        assertEquals(199, stats.getLong("frames"))
        assertEquals(times.min(), stats.getLong("first_timestamp_ns"))
        assertEquals(times.max(), stats.getLong("last_timestamp_ns"))
        assertEquals(2 * period / 1e6, stats.getDouble("max_gap_ms"), 1e-6)
        assertEquals(0, pool.dropped)
        assertEquals(0, pool.failed)
    }

    @Test
    fun aFullQueueDropsFramesInsteadOfBlockingAndCountsThem() {
        val pool = FrameWriterPool(1, 2, "test")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        assertTrue(pool.submit {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        })
        started.await(10, TimeUnit.SECONDS)
        // The writer is busy: two frames wait, the next two are dropped.
        val accepted = List(4) { pool.submit {} }
        assertEquals(listOf(true, true, false, false), accepted)
        assertEquals(2, pool.dropped)
        assertEquals(2, pool.peak)
        release.countDown()
        pool.finish()
        assertEquals(0, pool.waiting)
        val json = pool.statsJson("frames_dropped_by_writer")
        assertEquals(2, json.getInt("frames_dropped_by_writer"))
        assertEquals(2, json.getInt("write_queue_capacity_frames"))
    }

    @Test
    fun aFailedWriteIsCountedAndNotListed() {
        val cam = stream()
        val pool = FrameWriterPool(2, 10, "test")
        pool.submit { throw java.io.IOException("disk full") }
        pool.submit { cam.imageStored(5, "5,5") }
        pool.finish()
        cam.finish()
        assertEquals(1, pool.failed)
        assertEquals(listOf("5,5.png"), rows(File(cam.dir, "data.csv")))
        assertFalse(File(cam.dir, "data/5.png").exists()) // the test wrote no image, only the row
    }

    @Test
    fun videoRowsFollowTheFramesThatReachTheFile() {
        val dir = Files.createTempDirectory("cam").toFile()
        val cam = CameraStream("cam0", dir, "mkv", "timestamp_ns")
        assertFalse(File(dir, "data").exists())
        // Without a known timestamp, the presentation time (µs) is the frame's.
        cam.videoFrameWritten(2_000)
        cam.videoFrameWritten(1_000)
        cam.finish()
        assertEquals(listOf("1000000,1000000.png", "2000000,2000000.png"), rows(File(dir, "data.csv")))
        assertEquals(2, cam.statsJson().getLong("frames"))
    }
}
