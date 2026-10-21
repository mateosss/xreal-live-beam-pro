package com.xreal.stereoview.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.xreal.stereoview.util.Mat3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Geometric model of one camera stream: a pinhole with Android's lens distortion model, in
 * the stream's normalized image coordinates (0..1, x right, y down), plus the camera's
 * orientation and position in Android sensor (device) coordinates.
 */
class CameraModel(
    val label: String,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    /** k1, k2, k3 of LENS_DISTORTION, or zeros when the stream is not distorted (or unknown). */
    val radial: DoubleArray,
    /** p1, p2 of LENS_DISTORTION. */
    val tangential: DoubleArray,
    /** SENSOR_ORIENTATION as reported. */
    val sensorOrientation: Int,
    /** LENS_POSE_ROTATION as a device → camera rotation matrix, if reported. */
    private val factoryRotation: DoubleArray?,
    /** Optical center in device coordinates, meters. */
    val position: DoubleArray?,
    val intrinsicsSource: String,
) {
    /**
     * Rotation from device coordinates to camera coordinates (x right, y down, z forward),
     * for a camera whose image must be turned clockwise by [sensorOrientation] degrees to be
     * upright in the device's natural orientation. The factory rotation is used when
     * [useFactory] and it agrees with that within [MAX_POSE_DEVIATION_DEG] (directly or
     * inverted); the description says which was used.
     */
    fun deviceToCamera(sensorOrientation: Int, useFactory: Boolean): Pair<DoubleArray, String> {
        val nominal = nominalDeviceToCamera(sensorOrientation)
        val factory = factoryRotation
        if (!useFactory) return nominal to "sensor orientation $sensorOrientation°"
        if (factory == null) return nominal to "sensor orientation $sensorOrientation° (no factory rotation)"
        for ((candidate, name) in listOf(factory to "factory", Mat3.transpose(factory) to "factory (inverted)")) {
            val deviation = Mat3.angleDegrees(Mat3.mul(candidate, Mat3.transpose(nominal)))
            if (deviation < MAX_POSE_DEVIATION_DEG) return candidate to "%s, %.1f° from %d°".format(name, deviation, sensorOrientation)
        }
        val deviation = Mat3.angleDegrees(Mat3.mul(factory, Mat3.transpose(nominal)))
        return nominal to "sensor orientation $sensorOrientation° (factory rotation %.0f° off, ignored)".format(deviation)
    }

    val hasDistortion: Boolean get() = (radial + tangential).any { it != 0.0 }

    /** Tangent of the half field of view that is fully inside the image, per image axis. */
    val tanHalfFovX: Double get() = min(cx, 1 - cx) / fx
    val tanHalfFovY: Double get() = min(cy, 1 - cy) / fy

    /**
     * Squared normalized radius beyond which rays are drawn black: outside the image the
     * distortion polynomial can fold back and sample wrong pixels.
     */
    val maxRadiusSquared: Double
        get() {
            val x = max(cx, 1 - cx) / fx
            val y = max(cy, 1 - cy) / fy
            return (x * x + y * y) * 1.2
        }

    fun describe(): String = buildString {
        append("$label: f=(%.3f, %.3f) c=(%.3f, %.3f) [%s], FOV %.1f°×%.1f°".format(
            fx, fy, cx, cy, intrinsicsSource,
            Math.toDegrees(2 * atan(0.5 / fx)), Math.toDegrees(2 * atan(0.5 / fy)),
        ))
        if (hasDistortion) append(", distortion k=%s p=%s".format(radial.fmt(), tangential.fmt()))
        append(", sensor orientation $sensorOrientation°, factory rotation ${if (factoryRotation != null) "yes" else "no"}")
        position?.let { append(", position %s mm".format(it.map { v -> v * 1000 }.toDoubleArray().fmt(1))) }
    }

    private fun DoubleArray.fmt(decimals: Int = 4) = joinToString(prefix = "[", postfix = "]") { "%.${decimals}f".format(it) }

    companion object {
        /** Factory rotations further than this from the sensor orientation are not trusted. */
        private const val MAX_POSE_DEVIATION_DEG = 30.0

        /**
         * Builds the model for a stream of [streamSize] from [chars]. [undistort] says whether the
         * HAL's own distortion correction was turned off, so this model must apply LENS_DISTORTION.
         */
        fun from(label: String, chars: CameraCharacteristics, streamSize: Size, undistort: Boolean): CameraModel {
            val array = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val arrayW = array?.width()?.toDouble() ?: streamSize.width.toDouble()
            val arrayH = array?.height()?.toDouble() ?: streamSize.height.toDouble()

            // Intrinsics in pre-correction array pixels.
            var intrinsicsSource = "factory intrinsics"
            var fxPx: Double
            var fyPx: Double
            var cxPx: Double
            var cyPx: Double
            val k = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (k != null && k.size >= 4 && k[0] > 0 && k[1] > 0 && k[2] in 0f..arrayW.toFloat() && k[3] in 0f..arrayH.toFloat()) {
                fxPx = k[0].toDouble()
                fyPx = k[1].toDouble()
                cxPx = k[2].toDouble()
                cyPx = k[3].toDouble()
            } else {
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                fxPx = if (focal != null && physical != null && pixels != null && physical.width > 0) {
                    intrinsicsSource = "from focal length and sensor size"
                    focal * pixels.width / physical.width.toDouble()
                } else {
                    intrinsicsSource = "assumed 70° horizontal FOV"
                    arrayW / 2 / tan(Math.toRadians(35.0))
                }
                fyPx = fxPx
                cxPx = arrayW / 2
                cyPx = arrayH / 2
            }

            // The stream is the array scaled and center-cropped to the stream's aspect ratio.
            val aspect = streamSize.width.toDouble() / streamSize.height
            val cropW = if (arrayW / arrayH > aspect) arrayH * aspect else arrayW
            val cropH = if (arrayW / arrayH > aspect) arrayH else arrayW / aspect
            val offX = (arrayW - cropW) / 2
            val offY = (arrayH - cropH) / 2

            val distortion = chars.get(CameraCharacteristics.LENS_DISTORTION)?.takeIf { undistort && it.size >= 5 }
            val radial = distortion?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) } ?: DoubleArray(3)
            val tangential = distortion?.let { doubleArrayOf(it[3].toDouble(), it[4].toDouble()) } ?: DoubleArray(2)

            val q = chars.get(CameraCharacteristics.LENS_POSE_ROTATION)?.takeIf { it.size >= 4 }

            return CameraModel(
                label = label,
                fx = fxPx / cropW,
                fy = fyPx / cropH,
                cx = (cxPx - offX) / cropW,
                cy = (cyPx - offY) / cropH,
                radial = radial,
                tangential = tangential,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                factoryRotation = q?.let { Mat3.fromQuaternion(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) },
                position = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.takeIf { it.size >= 3 }
                    ?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) },
                intrinsicsSource = intrinsicsSource,
            )
        }

        /**
         * Device → camera rotation of a back camera whose image must be rotated clockwise by
         * [sensorOrientation] degrees to be upright in the device's natural orientation.
         */
        fun nominalDeviceToCamera(sensorOrientation: Int): DoubleArray {
            val t = Math.toRadians(sensorOrientation.toDouble())
            val x = doubleArrayOf(cos(t), -sin(t), 0.0)  // image right
            val y = doubleArrayOf(-sin(t), -cos(t), 0.0) // image down
            val z = doubleArrayOf(0.0, 0.0, -1.0)        // out of the back of the device
            return Mat3.fromRows(x, y, z)
        }
    }
}
