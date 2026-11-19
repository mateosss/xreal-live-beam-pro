package com.xreal.stereoview.record

import com.xreal.stereoview.glasses.GlassesBasalt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sqrt

class BasaltCalibTest {
    @Test
    fun quaternionRoundTrips() {
        for (q in listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.9973, 0.0019, -0.0448, 0.0573), doubleArrayOf(0.5, -0.5, 0.5, 0.5))) {
            val n = sqrt(q.sumOf { it * it })
            val unit = DoubleArray(4) { q[it] / n }.let { u -> if (u[3] < 0) DoubleArray(4) { -u[it] } else u }
            val back = BasaltCalib.quaternion(BasaltCalib.matrix(unit[0], unit[1], unit[2], unit[3]))
            for (i in 0..3) assertEquals(unit[i], back[i], 1e-9)
        }
    }

    @Test
    fun fileHasBasaltsFields() {
        val cam = BasaltCalib.Camera("kb4", BasaltCalib.kb4(1.0, 2.0, 3.0, 4.0, doubleArrayOf(0.1, 0.2, 0.3, 0.4)), 640, 400,
            BasaltCalib.transform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.1, 0.2, 0.3)))
        val text = BasaltCalib.json(listOf(cam, cam), 400.0, "test")
        val v = JSONObject(text).getJSONObject("value0")
        for (key in listOf("T_imu_cam", "intrinsics", "resolution", "calib_accel_bias", "calib_gyro_bias", "imu_update_rate",
            "accel_noise_std", "gyro_noise_std", "accel_bias_std", "gyro_bias_std", "cam_time_offset_ns", "vignette")) {
            assertTrue(key, v.has(key))
        }
        assertEquals(9, v.getJSONArray("calib_accel_bias").length())
        assertEquals(12, v.getJSONArray("calib_gyro_bias").length())
        assertEquals(0.2, v.getJSONArray("T_imu_cam").getJSONObject(0).getDouble("py"), 0.0)
        assertEquals(1.0, v.getJSONArray("T_imu_cam").getJSONObject(0).getDouble("qw"), 0.0)
        val intr = v.getJSONArray("intrinsics").getJSONObject(1)
        assertEquals("kb4", intr.getString("camera_type"))
        assertEquals(listOf("fx", "fy", "cx", "cy", "k1", "k2", "k3", "k4"), intr.getJSONObject("intrinsics").keys().asSequence().toList())
        assertEquals("cam0 kb4 640×400, cam1 kb4 640×400", BasaltCalib.Custom.validate(text))
    }

    @Test
    fun oakFactoryMapsDepthaiModels() {
        fun cam(model: String, d: List<Double>) = JSONObject()
            .put("width", 640).put("height", 400).put("model", model)
            .put("intrinsics", JSONArray("[[400,0,320],[0,401,200],[0,0,1]]"))
            .put("distortion", JSONArray(d))
            .put("imu_from_camera", JSONArray("[[1,0,0,0.05],[0,1,0,0],[0,0,1,0],[0,0,0,1]]"))
        val cal = JSONObject()
            .put("left", cam("perspective", listOf(0.1, 0.2, 0.01, 0.02, 0.3, 0.4, 0.5, 0.6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
            .put("right", cam("fisheye", listOf(0.11, 0.12, 0.13, 0.14)))
        val v = JSONObject(OakRecording.basaltFactory(cal, listOf("left", "right"), 400.0).json!!).getJSONObject("value0")
        val left = v.getJSONArray("intrinsics").getJSONObject(0)
        assertEquals("pinhole-radtan8", left.getString("camera_type"))
        val li = left.getJSONObject("intrinsics")
        assertEquals(0.01, li.getDouble("p1"), 0.0)
        assertEquals(0.3, li.getDouble("k3"), 0.0)
        assertEquals(0.6, li.getDouble("k6"), 0.0)
        assertEquals("kb4", v.getJSONArray("intrinsics").getJSONObject(1).getString("camera_type"))
        assertEquals(0.05, v.getJSONArray("T_imu_cam").getJSONObject(0).getDouble("px"), 0.0)
        assertEquals(400.0, v.getDouble("imu_update_rate"), 0.0)
        assertEquals(null, OakRecording.basaltFactory(cal, listOf("left", "rgb"), 400.0).json)
    }

    /** Without camera-IMU extrinsics (depthai throws for them), the export falls back instead of disappearing. */
    @Test
    fun oakFactoryWithoutImuExtrinsicsFallsBack() {
        fun cam(withImu: Boolean, spec: Boolean) = JSONObject()
            .put("width", 640).put("height", 400).put("model", "perspective")
            .put("intrinsics", JSONArray("[[400,0,320],[0,401,200],[0,0,1]]"))
            .put("distortion", JSONArray(List(14) { 0.0 }))
            .put("left_from_camera", JSONArray("[[1,0,0,-0.075],[0,1,0,0],[0,0,1,0],[0,0,0,1]]"))
            .apply {
                if (withImu) put("imu_from_camera", JSONArray("[[1,0,0,0.05],[0,1,0,0],[0,0,1,0],[0,0,0,1]]"))
                if (spec) put("imu_from_camera_spec", JSONArray("[[1,0,0,0.04],[0,1,0,0],[0,0,1,0],[0,0,0,1]]"))
                if (!withImu) put("errors", JSONObject().put("imu_from_camera", "no IMU extrinsics"))
            }
        val spec = OakRecording.basaltFactory(JSONObject().put("left", cam(true, true)).put("right", cam(false, true)), listOf("left", "right"), 400.0)
        assertEquals(0.04, JSONObject(spec.json!!).getJSONObject("value0").getJSONArray("T_imu_cam").getJSONObject(0).getDouble("px"), 0.0)
        assertTrue(spec.notes.toString(), spec.notes.any { "DESIGN" in it })
        val rel = OakRecording.basaltFactory(JSONObject().put("left", cam(false, false)).put("right", cam(false, false)), listOf("left", "right"), 400.0)
        assertEquals(-0.075, JSONObject(rel.json!!).getJSONObject("value0").getJSONArray("T_imu_cam").getJSONObject(1).getDouble("px"), 0.0)
        assertTrue(rel.notes.toString(), rel.notes.any { "NO camera-IMU" in it })
    }

    /** Basalt's fisheye624 projection of a camera-frame point. */
    private fun project(p: JSONObject, x: Double, y: Double, z: Double): DoubleArray {
        val xp = x / z
        val yp = y / z
        val r = sqrt(xp * xp + yp * yp)
        val th = atan(r)
        val t2 = th * th
        val k = (1..6).map { p.getDouble("k$it") }
        var poly = k[5]
        for (i in 4 downTo 0) poly = k[i] + t2 * poly
        val td = th * (1 + t2 * poly)
        val xr = td * xp / r
        val yr = td * yp / r
        val rd2 = xr * xr + yr * yr
        val p1 = p.getDouble("p1"); val p2 = p.getDouble("p2")
        val s = (1..4).map { p.getDouble("s$it") }
        val xpp = xr + (2 * xr * xr + rd2) * p1 + 2 * xr * yr * p2 + s[0] * rd2 + s[1] * rd2 * rd2
        val ypp = yr + (2 * yr * yr + rd2) * p2 + 2 * xr * yr * p1 + s[2] * rd2 + s[3] * rd2 * rd2
        return doubleArrayOf(p.getDouble("fx") * xpp + p.getDouble("cx"), p.getDouble("fy") * ypp + p.getDouble("cy"))
    }

    @Test
    fun glassesFactoryTurnsTheModelWithTheImage() {
        val kc = listOf(0.02, 0.03, -0.03, 0.007, 0.001, -0.0005, 0.0012, -0.0009, 0.0007, -0.0003, 0.0004, 0.0002)
        fun dev(cx: Double) = JSONObject().put("resolution", JSONArray(listOf(640, 480))).put("fc", JSONArray(listOf(241.0, 240.0)))
            .put("cc", JSONArray(listOf(cx, 238.0))).put("kc", JSONArray(kc))
            .put("imu_q_cam", JSONArray(listOf(0.0, 0.0, 0.0, 1.0))).put("imu_p_cam", JSONArray(listOf(0.05, 0.0, 0.0)))
        val factory = JSONObject().put("SLAM_camera", JSONObject().put("device_1", dev(321.0)).put("device_2", dev(318.0)))
        val raw = listOf(dev(321.0), dev(318.0)).map { d ->
            JSONObject().put("fx", 241.0).put("fy", 240.0).put("cx", d.getJSONArray("cc").getDouble(0)).put("cy", 238.0).apply {
                (1..6).forEach { put("k$it", kc[it - 1]) }
                put("p1", kc[6]); put("p2", kc[7]); (1..4).forEach { put("s$it", kc[7 + it]) }
            }
        }
        val result = GlassesBasalt.factory(factory.toString(), 1000.0)
        val v = JSONObject(result.json).getJSONObject("value0")
        for (i in 0..1) {
            assertEquals("fisheye624", v.getJSONArray("intrinsics").getJSONObject(i).getString("camera_type"))
            assertEquals(JSONArray(listOf(480, 640)).toString(), v.getJSONArray("resolution").getJSONArray(i).toString())
            val upright = v.getJSONArray("intrinsics").getJSONObject(i).getJSONObject("intrinsics")
            // The same 3D point, in the raw camera frame and in the upright camera frame.
            for ((x, y, z) in listOf(Triple(0.3, -0.2, 1.0), Triple(-0.5, 0.4, 1.0), Triple(0.1, 0.6, 0.8))) {
                val a = project(raw[i], x, y, z)
                val b = if (i == 0) project(upright, y, -x, z) else project(upright, -y, x, z)
                // Left turned counter-clockwise: u' = v, v' = 639 − u; right clockwise: u' = 479 − v, v' = u.
                val expected = if (i == 0) doubleArrayOf(a[1], 639 - a[0]) else doubleArrayOf(479 - a[1], a[0])
                assertEquals(expected[0], b[0], 1e-9)
                assertEquals(expected[1], b[1], 1e-9)
            }
        }
        assertTrue(result.info.toString(), result.info.getString("source").contains("factory"))
        // No SLAM_camera: Monado's reference calibration of another unit.
        val fallback = GlassesBasalt.factory(null, 1000.0)
        assertEquals("kb4", JSONObject(fallback.json).getJSONObject("value0").getJSONArray("intrinsics").getJSONObject(0).getString("camera_type"))
    }
}
