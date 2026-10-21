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
    /** AE target range for [targetFps], or null to leave AE free. */
    val fpsRange: Range<Int>?,
    /** Requested frame rate; [sizes] only holds sizes both cameras can stream at this rate. */
    val targetFps: Int,
    /** Frame rates that can be requested: AE ranges, plus faster rates possible with manual exposure. */
    val availableFps: List<Int>,
    /** The subset of [availableFps] that auto exposure can run at. */
    val autoExposureFps: List<Int>,
    /** Fastest frame rate each of [sizes] supports on both cameras. */
    val maxFps: Map<Size, Double>,
    /** Manual exposure time and ISO are supported (MANUAL_SENSOR). */
    val manualSensor: Boolean,
    val exposureTimeRange: Range<Long>?,
    val sensitivityRange: Range<Int>?,
    val baselineMm: Float?,
    val leftFocal: Float,
    val rightFocal: Float,
    /** True when the lens baseline runs vertically in the current display orientation. */
    val baselineVertical: Boolean,
    /** HAL distortion correction can be turned off because both lenses report LENS_DISTORTION. */
    val undistortInShader: Boolean,
    /** Optical stabilization can be turned off; each lens' OIS moves its image independently. */
    val canDisableOis: Boolean,
    /** Rig to try if this one cannot be opened or configured. */
    val fallback: StereoRig? = null,
) {
    val deviceIds: List<String> get() = listOf(left.deviceId, right.deviceId).distinct()

    /** Identifies the camera pair independent of which side is left. */
    val key: String get() = listOf(left.label, right.label).sorted().joinToString("+")

    /** Eye index (0 = left camera, 1 = right camera) and target for each output on [deviceId]. */
    fun targetsFor(deviceId: String): List<Pair<Int, CameraTarget>> =
        listOf(0 to left, 1 to right).filter { it.second.deviceId == deviceId }

    val description: String
        get() = buildString {
            append("${mode.label}: L=${left.label} R=${right.label}")
            if (mode != RigMode.MONO) append(", focal %.2f/%.2f mm".format(leftFocal, rightFocal))
            baselineMm?.let { append(", baseline %.1f mm".format(it)) }
        }
}

private class Candidate(val target: CameraTarget, val chars: CameraCharacteristics, val hidden: Boolean = false) {
    val focal: Float = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
    val pose: FloatArray? = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes: Set<Size> = map?.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()

    /** Fastest frame rate for a SurfaceTexture stream of [size]. */
    fun maxFps(size: Size): Double {
        val ns = try {
            map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
        } catch (e: IllegalArgumentException) {
            0L
        }
        return if (ns > 0) 1e9 / ns else 30.0
    }
}

object CameraUtils {
    /** Camera IDs probed beyond the public list; vendors sometimes hide extra cameras from it. */
    private const val PROBE_IDS = 32

    /**
     * Every way to stream two different rear cameras, best first: matching focal lengths
     * outweigh everything, then hardware-synced logical cameras, then advertised concurrency,
     * then the widest baseline. Cameras missing from the public list are tried last.
     */
    fun stereoRigs(manager: CameraManager, displayRotation: Int, maxHeight: Int, targetFps: Int = 30): List<StereoRig> {
        val scored = mutableListOf<Pair<Float, StereoRig>>()
        val back = backCandidates(manager)

        for (logical in back.filter { isLogicalMultiCamera(it.chars) }) {
            val physicals = logical.chars.physicalCameraIds.mapNotNull { id ->
                characteristicsOrNull(manager, id)?.let { Candidate(CameraTarget(logical.target.deviceId, id), it) }
            }
            for ((a, b) in pairs(physicals)) {
                buildRig(RigMode.LOGICAL, a, b, logical.chars, displayRotation, maxHeight, targetFps)
                    ?.let { scored += pairScore(a, b, bonus = 2000f) to it }
            }
        }

        val concurrentSets = manager.concurrentCameraIds
        for ((a, b) in pairs(back)) {
            val ids = setOf(a.target.deviceId, b.target.deviceId)
            val concurrent = concurrentSets.any { it.containsAll(ids) }
            val hiddenPenalty = if (a.hidden || b.hidden) 500f else 0f
            buildRig(if (concurrent) RigMode.CONCURRENT else RigMode.DUAL, a, b, a.chars, displayRotation, maxHeight, targetFps)
                ?.let { scored += pairScore(a, b, bonus = (if (concurrent) 1000f else 0f) - hiddenPenalty) to it }
        }
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * The rig to start with, followed by a chain of fallbacks: [preferredKey]'s rig if it
     * exists, the best other stereo rigs, then one camera feeding both eyes.
     */
    fun selectRig(
        manager: CameraManager,
        displayRotation: Int,
        maxHeight: Int,
        preferredKey: String = "",
        targetFps: Int = 30,
    ): StereoRig? {
        val stereo = stereoRigs(manager, displayRotation, maxHeight, targetFps)
        val ordered = (stereo.filter { it.key == preferredKey } + stereo.filter { it.key != preferredKey })
            .take(MAX_STEREO_ATTEMPTS)
            .toMutableList()
        backCandidates(manager).firstOrNull { !it.hidden }?.let { primary ->
            buildRig(RigMode.MONO, primary, primary, primary.chars, displayRotation, maxHeight, targetFps)?.let(ordered::add)
        }
        // Link each rig to the next one as its fallback.
        return ordered.foldRight(null as StereoRig?) { rig, next -> rig.copy(fallback = next) }
    }

    private const val MAX_STEREO_ATTEMPTS = 4

    /** Back-facing cameras from the public list plus any that answer when probed by ID. */
    private fun backCandidates(manager: CameraManager): List<Candidate> {
        val listed = manager.cameraIdList.toList()
        val probed = (0 until PROBE_IDS).map(Int::toString).filter { it !in listed }
        return (listed + probed).mapNotNull { id ->
            val chars = characteristicsOrNull(manager, id) ?: return@mapNotNull null
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            Candidate(CameraTarget(id, null), chars, hidden = id !in listed)
        }
    }

    private fun characteristicsOrNull(manager: CameraManager, id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (e: Exception) {
        null
    }

    private fun pairs(candidates: List<Candidate>): List<Pair<Candidate, Candidate>> =
        candidates.indices.flatMap { i -> (i + 1 until candidates.size).map { j -> candidates[i] to candidates[j] } }
            .filter { (a, b) -> commonSizes(a, b).isNotEmpty() }

    private fun pairScore(a: Candidate, b: Candidate, bonus: Float): Float =
        bonus - focalMismatch(a, b) * 5000f + (baselineMm(a, b) ?: 0f)

    private fun focalMismatch(a: Candidate, b: Candidate): Float =
        abs(a.focal - b.focal) / maxOf(a.focal, b.focal, 1e-3f)

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

    private fun buildRig(
        mode: RigMode,
        a: Candidate,
        b: Candidate,
        deviceChars: CameraCharacteristics,
        displayRotation: Int,
        maxHeight: Int,
        targetFps: Int,
    ): StereoRig? {
        val common = commonSizes(a, b)
        fun maxFps(size: Size) = minOf(a.maxFps(size), b.maxFps(size))
        // Prefer sizes fast enough for the requested rate, trading resolution for frame rate.
        val fastEnough = common.filter { maxFps(it) >= targetFps - 0.5 }.toSet()
        val sizes = pickSizes(fastEnough.ifEmpty { common }, maxHeight)
        if (sizes.isEmpty()) return null

        val capabilities = deviceChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manualSensor = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val aeRates = deviceChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.upper }.orEmpty()
        val manualRates = if (manualSensor) listOf(60, 90, 120).filter { rate -> common.any { maxFps(it) >= rate - 0.5 } } else emptyList()

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
            fpsRange = pickFpsRange(deviceChars, targetFps),
            targetFps = targetFps,
            availableFps = (aeRates + manualRates + 30).distinct().sorted(),
            autoExposureFps = aeRates.distinct().sorted(),
            maxFps = sizes.associateWith(::maxFps),
            manualSensor = manualSensor,
            exposureTimeRange = deviceChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            sensitivityRange = deviceChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            baselineMm = if (mode == RigMode.MONO) null else baselineMm(a, b),
            leftFocal = left.focal,
            rightFocal = right.focal,
            undistortInShader = deviceChars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true &&
                a.chars.get(CameraCharacteristics.LENS_DISTORTION) != null &&
                b.chars.get(CameraCharacteristics.LENS_DISTORTION) != null,
            canDisableOis = deviceChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true,
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

    /**
     * A fixed [fps] range if available, else the range capped at [fps] with the highest floor,
     * else the fastest range below [fps]. A fixed rate keeps AE from lengthening exposures.
     */
    private fun pickFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper < fps }.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    /** Human-readable dump of every camera, for the Info dialog. */
    fun describeCameras(manager: CameraManager): String = buildString {
        val listed = manager.cameraIdList.toList()
        val physical = listed.flatMap { manager.getCameraCharacteristics(it).physicalCameraIds }
            .filter { it !in listed }.distinct()
        val probed = (0 until PROBE_IDS).map(Int::toString)
            .filter { it !in listed && it !in physical && characteristicsOrNull(manager, it) != null }
        appendLine("Listed: $listed  physical-only: $physical  hidden (probed): $probed")
        for (id in listed + physical + probed) {
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
            val tag = when (id) {
                in physical -> " (physical only)"
                in probed -> " (hidden, probed)"
                else -> ""
            }
            appendLine("Camera $id$tag — $facing")
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
                appendLine("  focal: ${it.joinToString { f -> "%.2f mm".format(f) }}")
            }
            appendLine("  pose: " + (c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                ?.joinToString(prefix = "[", postfix = "] m") { "%.4f".format(it) } ?: "n/a") +
                ", ref " + poseReference(c.get(CameraCharacteristics.LENS_POSE_REFERENCE)))
            appendLine("  sensor orientation: ${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}°, " +
                "hw level: ${hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { appendLine("  pixel array: $it, physical ${c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} mm") }
            appendLine("  active: ${c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}, pre-correction: ${c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)}")
            appendLine("  rotation (x,y,z,w): " + (c.get(CameraCharacteristics.LENS_POSE_ROTATION)?.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) } ?: "n/a"))
            appendLine("  intrinsics (fx,fy,cx,cy,s): " + (c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.joinToString(prefix = "[", postfix = "]") { "%.1f".format(it) } ?: "n/a"))
            appendLine("  distortion (k1,k2,k3,p1,p2): " + (c.get(CameraCharacteristics.LENS_DISTORTION)?.joinToString(prefix = "[", postfix = "]") { "%.5f".format(it) } ?: "n/a") +
                ", correction modes ${c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toList() ?: "n/a"}")
            appendLine("  OIS modes ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList()}, " +
                "EIS modes ${c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList()}")
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
            append(frameRateReport(c))
        }
        val concurrent = manager.concurrentCameraIds
        appendLine("Concurrent sets: " + if (concurrent.isEmpty()) "none" else concurrent.joinToString())
    }

    /** What limits the frame rate: capabilities, AE ranges, and the fastest rate per stream size. */
    private fun frameRateReport(c: CameraCharacteristics): String = buildString {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(::capabilityName).orEmpty()
        appendLine("  capabilities: ${caps.joinToString()}")
        appendLine("  AE fps ranges: ${c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString() ?: "n/a"}")
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            appendLine("  exposure time: %.3f–%.1f ms, max frame duration %.1f ms".format(
                it.lower / 1e6, it.upper / 1e6, (c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L) / 1e6,
            ))
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@buildString
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().sortedByDescending { it.width * it.height }
        appendLine("  max fps per preview size: " + sizes.joinToString { size ->
            val ns = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrDefault(0L)
            if (ns > 0) "$size %.0f".format(1e9 / ns) else "$size ?"
        })
        val highSpeed = map.highSpeedVideoSizes.orEmpty()
        if (highSpeed.isNotEmpty()) {
            appendLine("  high-speed video: " + highSpeed.joinToString { "$it ${map.getHighSpeedVideoFpsRangesFor(it).joinToString()}" })
        }
    }

    private fun capabilityName(cap: Int): String = when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "backward-compatible"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "manual post-processing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "read sensor settings"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "burst"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "private reprocessing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV reprocessing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "depth"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "motion tracking"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "logical multi-camera"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "monochrome"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "secure"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "system camera"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ultra high resolution"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "stream use case"
        else -> cap.toString()
    }

    /** Camera-related system properties, e.g. vendor lists of apps allowed to see extra cameras. */
    fun cameraSystemProperties(): String = try {
        ProcessBuilder("getprop").redirectErrorStream(true).start().inputStream.bufferedReader()
            .readLines()
            .filter { it.contains("camera", ignoreCase = true) || it.contains("aux", ignoreCase = true) }
            .joinToString("\n")
            .ifEmpty { "(none readable)" }
    } catch (e: Exception) {
        "(getprop unavailable: ${e.message})"
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
