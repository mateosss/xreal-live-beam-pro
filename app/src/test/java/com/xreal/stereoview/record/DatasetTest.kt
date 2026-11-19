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
    /** A small recording; [legacyImu]: raw IMU streams in imu/ (before 0.15.3) instead of mav0/imu0. */
    private fun sample(legacyImu: Boolean = false): File {
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
        val raw = if (legacyImu) File(dir, "imu") else File(dir, "mav0/imu0")
        raw.mkdirs()
        File(raw, "gyroscope.csv").writeText("timestamp_ns,arrival_ns,wx,wy,wz,accuracy\n" +
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
    fun rawImuStreamsAreReadFromImu0AndFromTheOldFolder() {
        for (legacy in listOf(false, true)) {
            val d = Dataset.load(sample(legacy))
            assertEquals(listOf("gyroscope"), d.streams.map { it.first })
            assertTrue(d.stats().any { it.name == Dataset.RAW_PREFIX + "gyroscope" })
        }
    }

    @Test
    fun trimKeepsOnlyTheRangeEverywhere() {
        for (legacy in listOf(false, true)) trim(legacy)
    }

    private fun trim(legacy: Boolean) {
        val d = Dataset.load(sample(legacy))
        val start = 1_000_000_000L + 2 * period
        val end = 1_000_000_000L + 7 * period
        val target = File(d.dir.parentFile, "trimmed")
        DatasetTrimmer.trim(d, start, end, target, null)
        val t = Dataset.load(target)
        assertEquals(listOf(2L, 3L, 6L, 7L).map { 1_000_000_000L + it * period }, t.cameras[0].timestamps.toList())
        assertEquals(4, File(target, "mav0/cam0/data").list()!!.size)
        assertEquals(5, File(target, "mav0/cam0/frames.csv").readLines().size)
        assertTrue(t.imu0!!.t.all { it in start..end })
        assertTrue(File(target, "mav0/imu0/gyroscope.csv").readLines().drop(1).all { it.substringBefore(',').toLong() in start..end })
        val meta = JSONObject(File(target, "metadata.json").readText())
        assertEquals(d.name, meta.getJSONObject("trimmed_from").getString("recording"))
        assertTrue(File(target, "mav0/cam0/sensor.yaml").exists())
        assertFalse(File(target.parentFile, "trimmed.part").exists())
    }
}

class LiveImu0Test {
    @Test
    fun liveMergeMatchesTheMergeAtTheEnd() {
        val dir = Files.createTempDirectory("imu").toFile()
        val gyro = File(dir, "g.csv")
        val accel = File(dir, "a.csv")
        val g = (0 until 300).map { 1_000_000L + it * 2_500_000L to floatArrayOf(it * 0.01f, -it * 0.02f, 0.5f) }
        val a = (0 until 120).map { 3_000_000L + it * 6_000_000L to floatArrayOf(it.toFloat(), 9.8f, -it * 0.1f) }
        gyro.writeText("timestamp_ns,arrival_ns,wx,wy,wz\n" + g.joinToString("") { (t, v) -> "$t,0,${v[0]},${v[1]},${v[2]}\n" })
        accel.writeText("timestamp_ns,arrival_ns,ax,ay,az\n" + a.joinToString("") { (t, v) -> "$t,0,${v[0]},${v[1]},${v[2]}\n" })
        Euroc.mergeImu(gyro, accel, File(dir, "end.csv"))
        val live = LiveImu0(File(dir, "live.csv"))
        // Interleave as two threads might: accel in bursts, gyro steadily.
        var j = 0
        for ((t, v) in g) {
            live.gyro(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            while (j < a.size && a[j].first <= t + 10_000_000L) {
                val (ta, va) = a[j++]
                live.accel(ta, va[0].toDouble(), va[1].toDouble(), va[2].toDouble())
            }
        }
        while (j < a.size) a[j++].let { (ta, va) -> live.accel(ta, va[0].toDouble(), va[1].toDouble(), va[2].toDouble()) }
        live.close()
        val end = File(dir, "end.csv").readLines()
        val got = File(dir, "live.csv").readLines()
        assertEquals(end.size, got.size)
        for ((x, y) in end.zip(got).drop(1)) {
            val p = x.split(',').map { it.toDouble() }
            val q = y.split(',').map { it.toDouble() }
            assertEquals(p[0], q[0], 0.0)
            for (k in 1 until 7) assertEquals(p[k], q[k], 1e-5)
        }
    }
}
