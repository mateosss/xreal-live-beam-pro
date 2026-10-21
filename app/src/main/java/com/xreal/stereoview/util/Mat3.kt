package com.xreal.stereoview.util

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small helpers for row-major 3×3 matrices and 3-vectors stored in DoubleArrays. */
object Mat3 {
    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun fromRows(x: DoubleArray, y: DoubleArray, z: DoubleArray) =
        doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])

    fun row(m: DoubleArray, i: Int) = doubleArrayOf(m[3 * i], m[3 * i + 1], m[3 * i + 2])

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    fun mul(vararg ms: DoubleArray): DoubleArray = ms.reduce { a, b -> mul(a, b) }

    fun mulVec(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
        m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2]
    }

    fun transpose(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    /** Rotation about the x axis by [rad] (right-handed). */
    fun rotX(rad: Double): DoubleArray {
        val c = cos(rad)
        val s = sin(rad)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    /** Rotation about the z axis by [rad] (right-handed). */
    fun rotZ(rad: Double): DoubleArray {
        val c = cos(rad)
        val s = sin(rad)
        return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    /** Rotation matrix of the unit quaternion (x, y, z, w). */
    fun fromQuaternion(x: Double, y: Double, z: Double, w: Double): DoubleArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 0 } ?: 1.0
        val qx = x / n
        val qy = y / n
        val qz = z / n
        val qw = w / n
        return doubleArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    /** Unit quaternion (w, x, y, z) of the rotation matrix [m]. */
    fun toQuaternion(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[4] + m[8]
        val q = when {
            trace > 0 -> {
                val s = 2 * sqrt(trace + 1)
                doubleArrayOf(s / 4, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
            }
            m[0] > m[4] && m[0] > m[8] -> {
                val s = 2 * sqrt(1 + m[0] - m[4] - m[8])
                doubleArrayOf((m[7] - m[5]) / s, s / 4, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            }
            m[4] > m[8] -> {
                val s = 2 * sqrt(1 + m[4] - m[0] - m[8])
                doubleArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, s / 4, (m[5] + m[7]) / s)
            }
            else -> {
                val s = 2 * sqrt(1 + m[8] - m[0] - m[4])
                doubleArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, s / 4)
            }
        }
        return if (q[0] < 0) DoubleArray(4) { -q[it] } else q
    }

    /** Rotation angle of the rotation matrix [m], in degrees. */
    fun angleDegrees(m: DoubleArray): Double {
        val c = ((m[0] + m[4] + m[8] - 1) / 2).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** Column-major float copy, as glUniformMatrix3fv expects with transpose = false. */
    fun toGl(m: DoubleArray, out: FloatArray = FloatArray(9)): FloatArray {
        for (r in 0..2) for (c in 0..2) out[c * 3 + r] = m[r * 3 + c].toFloat()
        return out
    }

    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun norm(v: DoubleArray) = sqrt(dot(v, v))

    fun normalize(v: DoubleArray): DoubleArray {
        val n = norm(v).takeIf { it > 1e-12 } ?: return v.copyOf()
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
}
