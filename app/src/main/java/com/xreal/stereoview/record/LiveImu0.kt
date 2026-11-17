package com.xreal.stereoview.record

import java.io.File
import java.util.ArrayDeque

/**
 * Writes mav0/imu0/data.csv while recording, so a crash keeps it: one row per gyroscope sample
 * with the accelerometer linearly interpolated to its time, as soon as an accelerometer sample
 * at or after it has arrived (the same result as [Euroc.mergeImu] at the end). Gyroscope
 * samples before the first accelerometer sample are skipped. Thread-safe; the two sensors may
 * arrive on different threads and slightly out of step.
 */
class LiveImu0(file: File) {
    private val out = LineWriter(file, Euroc.IMU_HEADER)
    private class Sample(val t: Long, val x: Double, val y: Double, val z: Double)
    private val accel = ArrayDeque<Sample>()
    private val pendingGyro = ArrayDeque<Sample>()
    private val line = StringBuilder(160)

    var rows = 0L
        private set
    var firstNs = 0L
        private set
    var lastNs = 0L
        private set

    val rateHz: Double get() = if (rows > 1 && lastNs > firstNs) (rows - 1) * 1e9 / (lastNs - firstNs) else 0.0
    val error get() = out.error

    @Synchronized
    fun gyro(t: Long, x: Double, y: Double, z: Double) {
        pendingGyro.addLast(Sample(t, x, y, z))
        drain()
    }

    @Synchronized
    fun accel(t: Long, x: Double, y: Double, z: Double) {
        if (accel.isNotEmpty() && t <= accel.peekLast()!!.t) return
        accel.addLast(Sample(t, x, y, z))
        drain()
    }

    private fun drain() {
        while (pendingGyro.isNotEmpty() && accel.isNotEmpty()) {
            val g = pendingGyro.peekFirst()!!
            if (g.t < accel.peekFirst()!!.t) {
                pendingGyro.removeFirst() // before any accelerometer sample: skip
                continue
            }
            if (accel.peekLast()!!.t < g.t) return // wait for a later accelerometer sample
            // Bracket: drop accelerometer samples no longer needed (keep the one at or before g).
            while (accel.size >= 2 && secondT() <= g.t) accel.removeFirst()
            val p = accel.peekFirst()!!
            val q = if (accel.size >= 2) secondSample() else p
            val f = if (q.t != p.t) (g.t - p.t).toDouble() / (q.t - p.t) else 0.0
            write(g, p.x + f * (q.x - p.x), p.y + f * (q.y - p.y), p.z + f * (q.z - p.z))
            pendingGyro.removeFirst()
        }
        // Bound memory if one sensor stops: a gyroscope sample waits at most ~2 s.
        while (pendingGyro.size > 4096) pendingGyro.removeFirst()
    }

    private fun secondT(): Long = secondSample().t
    private fun secondSample(): Sample = accel.elementAt(1)

    private fun write(g: Sample, ax: Double, ay: Double, az: Double) {
        line.setLength(0)
        // Sensor values are floats: print them as such (no spurious digits).
        line.append(g.t).append(',').append(g.x.toFloat()).append(',').append(g.y.toFloat()).append(',').append(g.z.toFloat())
            .append(',').append(ax).append(',').append(ay).append(',').append(az)
        out.line(line)
        if (rows == 0L) firstNs = g.t
        lastNs = g.t
        rows++
    }

    fun close() = out.close()
}
