package com.xreal.stereoview.record

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatasetTest {
    private val period = 33_333_333L

    /** A small image recording: cam0 with frames 0..9 except 4 and 5, imu0 at 100 Hz, a raw stream. */
    private fun sample(): File {
        val dir = File(Files.createTempDirectory("ds").toFile(), "2026-01-01_00-00-00")
        val cam = File(dir, "mav0/cam0/data").apply { mkdirs() }.parentFile!!
        val frames = (0 until 10).filter { it != 4 && it != 5 }.map { 1_000_000_000L + it * period }
        File(cam, "data.csv").writeText(Euroc.CAMERA_HEADER + "\n" + frames.joinToString("") { "$it,$it.png\n" })
        frames.forEach { File(cam, "data/$it.png").writeText("x") }
        File(cam, "frames.csv").writeText("timestamp_ns,sequence\n" + frames.joinToString("") { "$it,1\n" })
        File(cam, "sensor.yaml").writeText("sensor_type: camera\n")
        File(dir, "mav0/imu0").mkdirs()
        File(dir, "mav0/imu0/data.csv").writeText(Euroc.IMU_HEADER + "\n" +
            (0 until 40).joinToString("") { "${1_000_000_000L + it * 10_000_000L},0.1,0.2,0.3,0,0,9.8\n" })
        File(dir, "imu").mkdirs()
        File(dir, "imu/gyroscope.csv").writeText("timestamp_ns,arrival_ns,wx,wy,wz,accuracy\n" +
            (0 until 40).joinToString("") { "${1_000_000_000L + it * 10_000_000L},0,0,0,0,\n" })
        File(dir, "metadata.json").writeText(JSONObject().put("source", "BEAM_PRO").put("format_version", 2).toString())
        return dir
    }

    @Test
    fun statsFindTheGapAndDeltas() {
        val d = Dataset.load(sample())
        val cam = d.stats().first { it.name == "cam0" }
        assertEquals(8, cam.count)
        assertEquals(2, cam.droppedEstimate)
        assertEquals(period, cam.minDeltaNs)
        assertEquals(period * 3, cam.maxDeltaNs)
        assertEquals(period, cam.medianDeltaNs)
        assertEquals(1, cam.gapStarts.size)
        val imu = d.stats().first { it.name == "imu0" }
        assertEquals(0, imu.droppedEstimate)
        assertEquals(100.0, imu.rateHz, 1e-6)
        assertEquals(3, d.stats().size)
        assertEquals(3, d.cameras[0].frameAt(1_000_000_000L + 6 * period - 1))
    }

    @Test
    fun trimKeepsOnlyTheRangeEverywhere() {
        val d = Dataset.load(sample())
        val start = 1_000_000_000L + 2 * period
        val end = 1_000_000_000L + 7 * period
        val target = File(d.dir.parentFile, "trimmed")
        DatasetTrimmer.trim(d, start, end, target, null)
        val t = Dataset.load(target)
        assertEquals(listOf(2L, 3L, 6L, 7L).map { 1_000_000_000L + it * period }, t.cameras[0].timestamps.toList())
        assertEquals(4, File(target, "mav0/cam0/data").list()!!.size)
        assertEquals(5, File(target, "mav0/cam0/frames.csv").readLines().size)
        assertTrue(t.imu0!!.t.all { it in start..end })
        assertTrue(File(target, "imu/gyroscope.csv").readLines().drop(1).all { it.substringBefore(',').toLong() in start..end })
        val meta = JSONObject(File(target, "metadata.json").readText())
        assertEquals(d.name, meta.getJSONObject("trimmed_from").getString("recording"))
        assertTrue(File(target, "mav0/cam0/sensor.yaml").exists())
        assertFalse(File(target.parentFile, "trimmed.part").exists())
    }
}
