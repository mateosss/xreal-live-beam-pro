package com.xreal.stereoview.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs
import kotlin.math.hypot

/** One stream source: a camera device, optionally narrowed to one of its physical cameras. */
data class CameraTarget(val deviceId: String, val physicalId: String?) {
    val label: String get() = physicalId?.let { "$deviceId/$it" } ?: deviceId
}

enum class RigMode(val label: String) {
    LOGICAL("logical multi-camera"),
    CONCURRENT("concurrent cameras"),
    DUAL("dual cameras (concurrency not advertised)"),
    MONO("single camera, 2D"),
}

/**
 * Everything needed to stream a left and right image. [left] and [right] may live on one
 * device (logical multi-camera or mono) or on two devices opened side by side.
 */
data class StereoRig(
    val mode: RigMode,
    val left: CameraTarget,
    val right: CameraTarget,
    /** Candidate stream sizes, best first; smaller ones are tried if a session fails. */
    val sizes: List<Size>,
    val sensorOrientation: Int,
    val exposureRange: Range<Int>,
    val fpsRange: Range<Int>?,
    val baselineMm: Float?,
    /** True when the lens baseline runs vertically in the current display orientation. */
    val baselineVertical: Boolean,
    /** Rig to try if this one cannot be opened or configured. */
    val fallback: StereoRig? = null,
) {
    val deviceIds: List<String> get() = listOf(left.deviceId, right.deviceId).distinct()

    /** Eye index (0 = left camera, 1 = right camera) and target for each output on [deviceId]. */
    fun targetsFor(deviceId: String): List<Pair<Int, CameraTarget>> =
        listOf(0 to left, 1 to right).filter { it.second.deviceId == deviceId }

    val description: String
        get() = buildString {
            append("${mode.label}: L=${left.label} R=${right.label}")
            baselineMm?.let { append(", baseline %.1f mm".format(it)) }
        }
}

private class Candidate(val target: CameraTarget, val chars: CameraCharacteristics) {
    val focal: Float = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
    val pose: FloatArray? = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
    val sizes: Set<Size> = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
}

object CameraUtils {
    /** Pairs whose focal lengths differ by more than this are not treated as a stereo pair. */
    private const val MAX_FOCAL_MISMATCH = 0.15f

    /**
     * Picks the best way to get two rear camera streams, with a chain of fallbacks:
     * logical multi-camera (hardware synced) → concurrent pair → plain dual open → mono.
     */
    fun selectRig(manager: CameraManager, displayRotation: Int, maxHeight: Int): StereoRig? {
        val backIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        if (backIds.isEmpty()) return null
        val rigs = mutableListOf<StereoRig>()

        for (id in backIds) {
            val chars = manager.getCameraCharacteristics(id)
            if (!isLogicalMultiCamera(chars)) continue
            val physicals = chars.physicalCameraIds.map {
                Candidate(CameraTarget(id, it), manager.getCameraCharacteristics(it))
            }
            bestPair(physicals)?.let { (a, b) ->
                buildRig(RigMode.LOGICAL, a, b, chars, displayRotation, maxHeight)?.let(rigs::add)
            }
        }

        val standalone = backIds.map { Candidate(CameraTarget(it, null), manager.getCameraCharacteristics(it)) }
        bestPair(standalone)?.let { (a, b) ->
            val ids = setOf(a.target.deviceId, b.target.deviceId)
            val concurrent = manager.concurrentCameraIds.any { it.containsAll(ids) }
            val mode = if (concurrent) RigMode.CONCURRENT else RigMode.DUAL
            buildRig(mode, a, b, a.chars, displayRotation, maxHeight)?.let(rigs::add)
        }

        val primary = standalone.first()
        buildRig(RigMode.MONO, primary, primary, primary.chars, displayRotation, maxHeight)?.let(rigs::add)

        // Link each rig to the next one as its fallback.
        return rigs.foldRight(null as StereoRig?) { rig, next -> rig.copy(fallback = next) }
    }

    fun rotationDegrees(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** Clockwise rotation that makes a back camera's image upright on the current display. */
    fun imageRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        (sensorOrientation - displayRotationDegrees + 360) % 360

    private fun isLogicalMultiCamera(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

    /** The pair with matching focal lengths and, among those, the widest baseline. */
    private fun bestPair(candidates: List<Candidate>): Pair<Candidate, Candidate>? {
        var best: Pair<Candidate, Candidate>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            val a = candidates[i]
            val b = candidates[j]
            val mismatch = abs(a.focal - b.focal) / maxOf(a.focal, b.focal, 1e-3f)
            if (mismatch > MAX_FOCAL_MISMATCH) continue
            if (commonSizes(a, b).isEmpty()) continue
            val score = -mismatch * 1000f + (baselineMm(a, b) ?: 0f)
            if (score > bestScore) {
                bestScore = score
                best = a to b
            }
        }
        return best
    }

    private fun buildRig(
        mode: RigMode,
        a: Candidate,
        b: Candidate,
        deviceChars: CameraCharacteristics,
        displayRotation: Int,
        maxHeight: Int,
    ): StereoRig? {
        val sizes = pickSizes(commonSizes(a, b), maxHeight)
        if (sizes.isEmpty()) return null

        // Lens poses are in Android sensor coordinates (x right, y up in the natural
        // orientation). Map them to screen axes; seen from behind a rear camera, the lens
        // further to screen-right is the right eye.
        var left = a
        var right = b
        var vertical = false
        if (mode != RigMode.MONO && a.pose != null && b.pose != null) {
            val dx = screenX(b.pose, displayRotation) - screenX(a.pose, displayRotation)
            val dy = screenY(b.pose, displayRotation) - screenY(a.pose, displayRotation)
            if (dx < 0) {
                left = b
                right = a
            }
            vertical = abs(dy) > abs(dx)
        }

        return StereoRig(
            mode = mode,
            left = left.target,
            right = right.target,
            sizes = sizes,
            sensorOrientation = deviceChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            exposureRange = deviceChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            fpsRange = pickFpsRange(deviceChars),
            baselineMm = if (mode == RigMode.MONO) null else baselineMm(a, b),
            baselineVertical = vertical,
        )
    }

    private fun screenX(p: FloatArray, rotation: Int): Float = when (rotation) {
        Surface.ROTATION_90 -> -p[1]
        Surface.ROTATION_180 -> -p[0]
        Surface.ROTATION_270 -> p[1]
        else -> p[0]
    }

    private fun screenY(p: FloatArray, rotation: Int): Float = when (rotation) {
        Surface.ROTATION_90 -> p[0]
        Surface.ROTATION_180 -> -p[1]
        Surface.ROTATION_270 -> -p[0]
        else -> p[1]
    }

    private fun baselineMm(a: Candidate, b: Candidate): Float? {
        val pa = a.pose ?: return null
        val pb = b.pose ?: return null
        return hypot(pa[0] - pb[0], pa[1] - pb[1]) * 1000f
    }

    private fun commonSizes(a: Candidate, b: Candidate): Set<Size> = a.sizes intersect b.sizes

    /** Sizes up to [maxHeight], 16:9 first, then by area; at least the smallest size as a last resort. */
    private fun pickSizes(sizes: Set<Size>, maxHeight: Int): List<Size> {
        val fitting = sizes.filter { it.height <= maxHeight && it.width <= maxHeight * 2 }
        val sorted = fitting.sortedWith(
            compareByDescending<Size> { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.01f }
                .thenByDescending { it.width * it.height }
        )
        return sorted.take(4).ifEmpty { listOfNotNull(sizes.minByOrNull { it.width * it.height }) }
    }

    /** Prefer a fixed 30 fps; otherwise the range capped at 30 with the highest floor. */
    private fun pickFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
    }

    /** Human-readable dump of every camera, for the Info dialog. */
    fun describeCameras(manager: CameraManager): String = buildString {
        val listed = manager.cameraIdList.toList()
        val physical = listed.flatMap { manager.getCameraCharacteristics(it).physicalCameraIds }
            .filter { it !in listed }.distinct()
        for (id in listed + physical) {
            val c = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                appendLine("Camera $id: ${e.message}")
                continue
            }
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "?"
            }
            appendLine("Camera $id${if (id in physical) " (physical only)" else ""} — $facing")
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
                appendLine("  focal: ${it.joinToString { f -> "%.2f mm".format(f) }}")
            }
            appendLine("  pose: " + (c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                ?.joinToString(prefix = "[", postfix = "] m") { "%.4f".format(it) } ?: "n/a") +
                ", ref " + poseReference(c.get(CameraCharacteristics.LENS_POSE_REFERENCE)))
            appendLine("  sensor orientation: ${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}°, " +
                "hw level: ${hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { appendLine("  pixel array: $it") }
            if (isLogicalMultiCamera(c)) {
                val sync = when (c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)) {
                    CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "calibrated"
                    CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "approximate"
                    else -> "?"
                }
                appendLine("  logical multi-camera: physicals ${c.physicalCameraIds}, sync $sync")
            }
            appendLine("  sync max latency: " + when (val l = c.get(CameraCharacteristics.SYNC_MAX_LATENCY)) {
                CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL -> "per-frame"
                CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN -> "unknown"
                null -> "n/a"
                else -> "$l frames"
            })
            appendLine("  timestamps: " + when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                else -> "unknown source"
            })
        }
        val concurrent = manager.concurrentCameraIds
        appendLine("Concurrent sets: " + if (concurrent.isEmpty()) "none" else concurrent.joinToString())
    }

    private fun poseReference(ref: Int?): String = when (ref) {
        CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "primary camera"
        CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "gyroscope"
        CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED -> "undefined"
        CameraMetadata.LENS_POSE_REFERENCE_AUTOMOTIVE -> "automotive"
        else -> "n/a"
    }

    private fun hardwareLevel(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level 3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
        else -> "?"
    }
}
