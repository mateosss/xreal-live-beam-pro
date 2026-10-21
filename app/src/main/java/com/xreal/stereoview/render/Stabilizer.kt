package com.xreal.stereoview.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.xreal.stereoview.util.Mat3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gyro-based rotational stabilization. Tracks the device orientation and a smoothed copy of it;
 * rendering the view from the smoothed orientation removes hand shake while following
 * deliberate motion. The same correction is applied to both eyes, so stereo alignment holds.
 */
class Stabilizer(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    private class Sample(val timestampNs: Long, val quaternion: DoubleArray) // (w, x, y, z)

    private val history = ArrayDeque<Sample>()
    private val scratch = FloatArray(4)

    // GL thread state
    private var smoothed: DoubleArray? = null
    private var lastTimestampNs = 0L

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(history) { history.clear() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(scratch, event.values)
        val q = DoubleArray(4) { scratch[it].toDouble() }
        synchronized(history) {
            history.addLast(Sample(event.timestamp, q))
            while (history.size > HISTORY_SIZE) history.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Rotation (device frame) that turns the current view into the smoothed one, for the frame
     * captured at [frameTimestampNs]. [timeConstantSec] ≤ 0 disables stabilization.
     */
    fun correction(frameTimestampNs: Long, timeConstantSec: Double, maxAngleDeg: Double = MAX_CORRECTION_DEG): DoubleArray {
        val sample = sampleNear(frameTimestampNs)
        if (timeConstantSec <= 0 || sample == null) {
            smoothed = null
            return Mat3.identity()
        }
        val current = sample.quaternion
        val previous = smoothed
        if (previous == null) {
            smoothed = current
            lastTimestampNs = sample.timestampNs
            return Mat3.identity()
        }
        val dt = ((sample.timestampNs - lastTimestampNs) / 1e9).coerceIn(0.0, 0.2)
        lastTimestampNs = sample.timestampNs
        var next = slerp(previous, current, 1 - exp(-dt / timeConstantSec))
        val angle = angleDegrees(next, current)
        if (angle > maxAngleDeg) next = slerp(current, next, maxAngleDeg / angle)
        smoothed = next
        // world ← device matrices; current-device ← smoothed-device = R(current)ᵀ · R(smoothed)
        return Mat3.mul(Mat3.transpose(matrix(current)), matrix(next))
    }

    /** The orientation sample closest to the camera frame, or the latest if clocks don't match. */
    private fun sampleNear(timestampNs: Long): Sample? = synchronized(history) {
        val latest = history.lastOrNull() ?: return null
        if (abs(timestampNs - latest.timestampNs) > 500_000_000L) return latest
        history.minByOrNull { abs(it.timestampNs - timestampNs) }
    }

    private fun matrix(q: DoubleArray) = Mat3.fromQuaternion(q[1], q[2], q[3], q[0])

    private fun angleDegrees(a: DoubleArray, b: DoubleArray): Double {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1.0)
        return Math.toDegrees(2 * acos(d))
    }

    private fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val end = if (dot < 0) DoubleArray(4) { -b[it] }.also { dot = -dot } else b
        if (dot > 0.9995) {
            val r = DoubleArray(4) { a[it] + t * (end[it] - a[it]) }
            val n = sqrt(r.sumOf { it * it })
            return DoubleArray(4) { r[it] / n }
        }
        val theta = acos(dot)
        val wa = sin((1 - t) * theta) / sin(theta)
        val wb = sin(t * theta) / sin(theta)
        return DoubleArray(4) { wa * a[it] + wb * end[it] }
    }

    private companion object {
        const val HISTORY_SIZE = 400
        const val MAX_CORRECTION_DEG = 8.0
    }
}
