package com.xreal.stereoview.record

import java.io.File
import java.util.Locale

/**
 * The EuRoC MAV dataset layout that recordings are written in:
 *
 *     mav0/body.yaml
 *     mav0/camN/data.csv      #timestamp [ns],filename
 *     mav0/camN/data/<timestamp>.png   (or data.mp4 for video recordings)
 *     mav0/camN/sensor.yaml
 *     mav0/imu0/data.csv      #timestamp [ns],w_RS_S_x…,a_RS_S_z
 *     mav0/imu0/sensor.yaml
 *
 * as read by Basalt, ORB-SLAM3, OpenVINS, VINS-Fusion and Kalibr. Extra per-frame details go
 * in other files next to these, which those readers ignore.
 */
object Euroc {
    const val CAMERA_HEADER = "#timestamp [ns],filename"
    const val IMU_HEADER = "#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1]," +
        "a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]"

    fun mav(dir: File) = File(dir, "mav0")

    /** Row-major 4×4 transform from a row-major 3×3 [rotation] and a [translation] (zero if null). */
    fun transform(rotation: DoubleArray, translation: DoubleArray?): DoubleArray {
        val t = translation ?: DoubleArray(3)
        return doubleArrayOf(
            rotation[0], rotation[1], rotation[2], t[0],
            rotation[3], rotation[4], rotation[5], t[1],
            rotation[6], rotation[7], rotation[8], t[2],
            0.0, 0.0, 0.0, 1.0,
        )
    }

    fun cameraYaml(
        notes: List<String>,
        comment: String,
        tBS: DoubleArray,
        rateHz: Double,
        width: Int,
        height: Int,
        intrinsics: DoubleArray,
        distortionModel: String,
        distortion: DoubleArray,
    ): String = buildString {
        notes.forEach { appendLine("# $it") }
        appendLine("# General sensor definitions.")
        appendLine("sensor_type: camera")
        appendLine("comment: $comment")
        appendLine()
        appendLine("# Sensor extrinsics wrt. the body-frame.")
        appendTransform(tBS)
        appendLine()
        appendLine("# Camera specific definitions.")
        appendLine("rate_hz: ${fmt(rateHz, 3)}")
        appendLine("resolution: [$width, $height]")
        appendLine("camera_model: pinhole")
        appendLine("intrinsics: ${list(intrinsics, 6)} #fu, fv, cu, cv")
        appendLine("distortion_model: $distortionModel")
        appendLine("distortion_coefficients: ${list(distortion, 9)}")
    }

    /** IMU sensor.yaml; the noise values are generic placeholders unless given. */
    fun imuYaml(notes: List<String>, comment: String, tBS: DoubleArray, rateHz: Double): String = buildString {
        notes.forEach { appendLine("# $it") }
        appendLine("# Noise values are placeholders: measure yours (e.g. Allan variance) before relying on them.")
        appendLine("sensor_type: imu")
        appendLine("comment: $comment")
        appendLine()
        appendLine("# Sensor extrinsics wrt. the body-frame.")
        appendTransform(tBS)
        appendLine("rate_hz: ${fmt(rateHz, 1)}")
        appendLine()
        appendLine("# inertial sensor noise model parameters (static)")
        appendLine("gyroscope_noise_density: 1.0e-3     # [ rad / s / sqrt(Hz) ]   ( gyro \"white noise\" )")
        appendLine("gyroscope_random_walk: 1.0e-5       # [ rad / s^2 / sqrt(Hz) ] ( gyro bias diffusion )")
        appendLine("accelerometer_noise_density: 1.0e-2  # [ m / s^2 / sqrt(Hz) ]   ( accel \"white noise\" )")
        appendLine("accelerometer_random_walk: 1.0e-4    # [ m / s^3 / sqrt(Hz) ].  ( accel bias diffusion )")
    }

    fun bodyYaml(comment: String) = "# This is the body frame definition\ncomment: '$comment'\n"

    /**
     * Writes imu0/data.csv from ImuRecorder's [gyro] and [accel] files (timestamp_ns, arrival_ns,
     * x, y, z, …): one row per gyroscope sample, with the accelerometer linearly interpolated to
     * its time (gyroscope samples outside the accelerometer's span are skipped). Returns the
     * number of rows and the rate.
     */
    fun mergeImu(gyro: File, accel: File, out: File): Pair<Long, Double> {
        val a = readXyz(accel)
        var rows = 0L
        var first = Long.MIN_VALUE
        var last = Long.MIN_VALUE
        var j = 0
        out.bufferedWriter().use { w ->
            w.write(IMU_HEADER)
            w.write("\n")
            gyro.bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    val c = line.split(',')
                    if (c.size < 5) continue
                    val t = c[0].toLongOrNull() ?: continue
                    while (j + 1 < a.size && a[j + 1].t < t) j++
                    if (j + 1 >= a.size || a[j].t > t) continue
                    val p = a[j]
                    val q = a[j + 1]
                    val f = if (q.t != p.t) (t - p.t).toDouble() / (q.t - p.t) else 0.0
                    w.write("$t,${c[2]},${c[3]},${c[4]}")
                    for (k in 0..2) w.write("," + g(p.v[k] + f * (q.v[k] - p.v[k])))
                    w.write("\n")
                    if (rows == 0L) first = t
                    last = t
                    rows++
                }
            }
        }
        val rate = if (rows > 1) (rows - 1) * 1e9 / (last - first) else 0.0
        return rows to rate
    }

    /** Rewrites a CSV file with its rows (after the header) sorted by the numeric first column. */
    fun sortByTimestamp(file: File) {
        if (!file.exists()) return
        val lines = file.readLines()
        if (lines.size < 3) return
        val sorted = lines.drop(1).filter { it.isNotEmpty() }.sortedBy { it.substringBefore(',').toLongOrNull() ?: Long.MAX_VALUE }
        file.writeText((listOf(lines[0]) + sorted).joinToString("\n", postfix = "\n"))
    }

    private class Sample(val t: Long, val v: DoubleArray)

    private fun readXyz(file: File): List<Sample> = file.bufferedReader().useLines { lines ->
        lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 5) return@mapNotNull null
            val t = c[0].toLongOrNull() ?: return@mapNotNull null
            Sample(t, doubleArrayOf(c[2].toDouble(), c[3].toDouble(), c[4].toDouble()))
        }.toList()
    }

    private fun StringBuilder.appendTransform(m: DoubleArray) {
        appendLine("T_BS:")
        appendLine("  cols: 4")
        appendLine("  rows: 4")
        appendLine("  data: [" + (0 until 4).joinToString(",\n         ") { r -> (0 until 4).joinToString(", ") { c -> fmt(m[4 * r + c], 9) } } + "]")
    }

    private fun list(v: DoubleArray, decimals: Int) = v.joinToString(", ", "[", "]") { fmt(it, decimals) }
    private fun fmt(v: Double, decimals: Int) = "%.${decimals}f".format(Locale.US, v + 0.0) // + 0.0 turns -0.0 into 0.0
    private fun g(v: Double) = "%.9g".format(Locale.US, v)
}
