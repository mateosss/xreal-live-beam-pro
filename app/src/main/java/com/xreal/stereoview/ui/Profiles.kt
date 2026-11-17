package com.xreal.stereoview.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named set of capture settings: how to view and record. Device setup (camera pair, image
 * rotation, eye swap, alignment, glasses, output) is not part of a profile, so switching
 * profiles never undoes calibration.
 */
data class Profile(val name: String, val settings: StereoSettings) {
    /** Whether [current] differs from this profile in any profile setting. */
    fun differsFrom(current: StereoSettings): Boolean = current.withProfile(settings) != current

    /** One line describing the profile, for lists. */
    val summary: String
        get() = with(settings) {
            if (cameraSource == CameraSource.OAK) {
                return@with listOf(
                    "mono ${oakMonoHeight}p ${oakImageFormat.name}",
                    "$oakFps fps",
                    if (oakRgbHeight > 0) "color ${oakRgbHeight}p JPEG $oakJpegQuality" else "no color",
                    if (oakImuRateHz > 0) "OAK IMU $oakImuRateHz Hz" else "no OAK IMU",
                    if (manualExposure) "manual exposure ${exposureTimeNs / 1000} µs ISO $iso" else "auto exposure",
                ).joinToString(" · ")
            }
            if (cameraSource == CameraSource.GLASSES) {
                return@with listOf(
                    "glasses cameras 30 fps per eye, ${glassesImageFormat.name}",
                    if (glassesManualExposure) "exposure %.1f ms".format(glassesExposure100us / 10.0) else "auto exposure",
                    if (glassesRectify) "rectified preview" else "raw preview",
                    if (glassesImu) "glasses IMU taken over" else "glasses IMU left to the system",
                    "IMU ${imuRate.label.substringBefore(" (").replaceFirstChar { it.lowercase() }}",
                ).joinToString(" · ")
            }
            listOf(
                if (manualExposure) "manual exposure 1/${(1e9 / exposureTimeNs).toInt()} s ISO $iso" else "auto exposure",
                "$targetFps fps ≤${maxResolution}p",
                "stabilization ${stabilization.label.lowercase()}",
                if (focusMode == FocusMode.INFINITY) "focus ∞" else "autofocus",
                "records ${recordView.name.lowercase()} ${recordFormat.name}",
                "IMU " + imuRate.label.substringBefore(" (").replaceFirstChar { it.lowercase() },
            ).joinToString(" · ")
        }

    companion object {
        /** The built-in profiles, also restored by "Restore defaults". */
        val defaults: List<Profile>
            get() {
                val base = StereoSettings()
                return listOf(
                    Profile(PASSTHROUGH, base.copy(stabilization = Stabilization.OFF)),
                    Profile(
                        EUROC,
                        base.copy(
                            scaleMode = ScaleMode.FILL,
                            stabilization = Stabilization.OFF,
                            focusMode = FocusMode.INFINITY,
                            maxResolution = 720,
                            targetFps = 30,
                            recordView = RecordView.RAW,
                            recordFormat = RecordFormat.H264,
                            recordBitrateMbps = 60,
                            imuRate = ImuRate.DIRECT_VERY_FAST,
                            imuStreams = listOf(
                                "ACCELEROMETER", "GYROSCOPE", "ACCELEROMETER_UNCALIBRATED",
                                "GYROSCOPE_UNCALIBRATED", "MAGNETOMETER_UNCALIBRATED", "GAME_ROTATION_VECTOR",
                            ).joinToString(","),
                        ),
                    ),
                    Profile(
                        OAK_PASSTHROUGH,
                        base.copy(
                            cameraSource = CameraSource.OAK,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FIT,
                            oakMonoHeight = 720,
                            oakRgbHeight = 0,
                            oakImuRateHz = 0,
                        ),
                    ),
                    Profile(
                        OAK_EUROC,
                        base.copy(
                            cameraSource = CameraSource.OAK,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FIT,
                            stabilization = Stabilization.OFF,
                            oakMonoHeight = 800,
                            oakFps = 30,
                            oakRgbHeight = 800,
                            oakJpegQuality = 95,
                            oakImuRateHz = 200,
                            imuRate = ImuRate.DIRECT_VERY_FAST,
                            imuStreams = listOf("ACCELEROMETER", "GYROSCOPE", "ACCELEROMETER_UNCALIBRATED", "GYROSCOPE_UNCALIBRATED")
                                .joinToString(","),
                        ),
                    ),
                    Profile(
                        GLASSES_PASSTHROUGH,
                        base.copy(
                            cameraSource = CameraSource.GLASSES,
                            glassesImu = false,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FILL,
                        ),
                    ),
                    Profile(
                        GLASSES_EUROC,
                        base.copy(
                            cameraSource = CameraSource.GLASSES,
                            glassesImu = true,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FILL,
                            stabilization = Stabilization.OFF,
                            imuRate = ImuRate.DIRECT_VERY_FAST,
                            imuStreams = listOf("ACCELEROMETER", "GYROSCOPE", "ACCELEROMETER_UNCALIBRATED", "GYROSCOPE_UNCALIBRATED")
                                .joinToString(","),
                        ),
                    ),
                )
            }

        /**
         * Updates stored copies of built-in profiles whose defaults changed, if they still hold
         * one of their old defaults (never touched by the user): the glasses profiles now use
         * Fill (0.13), Passthrough has no stabilization (0.14).
         */
        fun migrate(profiles: List<Profile>): List<Profile> {
            val old: Map<String, List<(StereoSettings) -> StereoSettings>> = mapOf(
                GLASSES_PASSTHROUGH to listOf({ s -> s.copy(scaleMode = ScaleMode.REAL) }),
                GLASSES_EUROC to listOf({ s -> s.copy(scaleMode = ScaleMode.FIT) }),
                PASSTHROUGH to listOf({ s -> s.copy(stabilization = Stabilization.MEDIUM) }),
            )
            val base = StereoSettings()
            return profiles.map { p ->
                val olds = old[p.name] ?: return@map p
                val current = defaults.firstOrNull { it.name == p.name } ?: return@map p
                val stored = base.withProfile(p.settings)
                if (olds.any { stored == base.withProfile(it(current.settings)) }) current else p
            }
        }

        const val PASSTHROUGH = "Passthrough"
        const val EUROC = "EuRoC dataset"
        const val GLASSES_EUROC = "Glasses EuRoC dataset"
        const val GLASSES_PASSTHROUGH = "Glasses passthrough"
        const val OAK_PASSTHROUGH = "OAK passthrough"
        const val OAK_EUROC = "OAK EuRoC dataset"

        fun toJson(profiles: List<Profile>): String = JSONArray(profiles.map { p ->
            JSONObject().put("name", p.name).put("settings", JSONObject().apply {
                val s = p.settings
                put("convergencePx", s.convergencePx.toDouble())
                put("depth", s.depth.toDouble())
                put("scaleMode", s.scaleMode.name)
                put("exposure", s.exposure)
                put("manualExposure", s.manualExposure)
                put("exposureTimeNs", s.exposureTimeNs)
                put("iso", s.iso)
                put("targetFps", s.targetFps)
                put("maxResolution", s.maxResolution)
                put("syncThresholdMs", s.syncThresholdMs)
                put("stabilization", s.stabilization.name)
                put("focusMode", s.focusMode.name)
                put("recordView", s.recordView.name)
                put("recordFormat", s.recordFormat.name)
                put("recordBitrateMbps", s.recordBitrateMbps)
                put("imuRate", s.imuRate.name)
                put("imuStreams", s.imuStreams)
                put("cameraSource", s.cameraSource.name)
                put("glassesImu", s.glassesImu)
                put("glassesRectify", s.glassesRectify)
                put("glassesManualExposure", s.glassesManualExposure)
                put("glassesExposure100us", s.glassesExposure100us)
                put("glassesGain", s.glassesGain)
                put("oakMonoHeight", s.oakMonoHeight)
                put("oakFps", s.oakFps)
                put("oakRgbHeight", s.oakRgbHeight)
                put("oakJpegQuality", s.oakJpegQuality)
                put("oakImuRateHz", s.oakImuRateHz)
                put("oakUsb3", s.oakUsb3)
                put("glassesImageFormat", s.glassesImageFormat.name)
                put("oakImageFormat", s.oakImageFormat.name)
                put("grayJpegQuality", s.grayJpegQuality)
                put("videoBitrateMode", s.videoBitrateMode.name)
                put("videoKeyframeIntervalS", s.videoKeyframeIntervalS.toDouble())
                put("videoProfile", s.videoProfile.name)
                put("glassesVideoBitrateMbps", s.glassesVideoBitrateMbps)
                put("oakVideoBitrateMbps", s.oakVideoBitrateMbps)
            })
        }).toString()

        /** Parses [toJson]'s output; missing or unknown values take the defaults. */
        fun fromJson(text: String): List<Profile> {
            val array = JSONArray(text)
            val d = StereoSettings()
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val j = o.getJSONObject("settings")
                Profile(
                    o.getString("name"),
                    d.copy(
                        convergencePx = j.optDouble("convergencePx", d.convergencePx.toDouble()).toFloat(),
                        depth = j.optDouble("depth", d.depth.toDouble()).toFloat(),
                        scaleMode = enumOr(j.optString("scaleMode"), d.scaleMode),
                        exposure = j.optInt("exposure", d.exposure),
                        manualExposure = j.optBoolean("manualExposure", d.manualExposure),
                        exposureTimeNs = j.optLong("exposureTimeNs", d.exposureTimeNs),
                        iso = j.optInt("iso", d.iso),
                        targetFps = j.optInt("targetFps", d.targetFps),
                        maxResolution = j.optInt("maxResolution", d.maxResolution),
                        syncThresholdMs = j.optInt("syncThresholdMs", d.syncThresholdMs),
                        stabilization = enumOr(j.optString("stabilization"), d.stabilization),
                        focusMode = enumOr(j.optString("focusMode"), d.focusMode),
                        recordView = enumOr(j.optString("recordView"), d.recordView),
                        recordFormat = enumOr(j.optString("recordFormat"), d.recordFormat),
                        recordBitrateMbps = j.optInt("recordBitrateMbps", d.recordBitrateMbps),
                        imuRate = enumOr(j.optString("imuRate"), d.imuRate),
                        imuStreams = j.optString("imuStreams", d.imuStreams),
                        cameraSource = enumOr(j.optString("cameraSource"), d.cameraSource),
                        glassesImu = j.optBoolean("glassesImu", d.glassesImu),
                        glassesRectify = j.optBoolean("glassesRectify", d.glassesRectify),
                        glassesManualExposure = j.optBoolean("glassesManualExposure", d.glassesManualExposure),
                        glassesExposure100us = j.optInt("glassesExposure100us", d.glassesExposure100us),
                        glassesGain = j.optInt("glassesGain", d.glassesGain),
                        oakMonoHeight = j.optInt("oakMonoHeight", d.oakMonoHeight),
                        oakFps = j.optInt("oakFps", d.oakFps),
                        oakRgbHeight = j.optInt("oakRgbHeight", d.oakRgbHeight),
                        oakJpegQuality = j.optInt("oakJpegQuality", d.oakJpegQuality),
                        oakImuRateHz = j.optInt("oakImuRateHz", d.oakImuRateHz),
                        oakUsb3 = j.optBoolean("oakUsb3", d.oakUsb3),
                        glassesImageFormat = enumOr(j.optString("glassesImageFormat"), d.glassesImageFormat),
                        oakImageFormat = enumOr(j.optString("oakImageFormat"), d.oakImageFormat),
                        grayJpegQuality = j.optInt("grayJpegQuality", d.grayJpegQuality),
                        videoBitrateMode = enumOr(j.optString("videoBitrateMode"), d.videoBitrateMode),
                        videoKeyframeIntervalS = j.optDouble("videoKeyframeIntervalS", d.videoKeyframeIntervalS.toDouble()).toFloat(),
                        videoProfile = enumOr(j.optString("videoProfile"), d.videoProfile),
                        glassesVideoBitrateMbps = j.optInt("glassesVideoBitrateMbps", d.glassesVideoBitrateMbps),
                        oakVideoBitrateMbps = j.optInt("oakVideoBitrateMbps", d.oakVideoBitrateMbps),
                    ),
                )
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}

/** These settings with every profile setting taken from [profile]. */
fun StereoSettings.withProfile(profile: StereoSettings) = copy(
    convergencePx = profile.convergencePx,
    depth = profile.depth,
    scaleMode = profile.scaleMode,
    exposure = profile.exposure,
    manualExposure = profile.manualExposure,
    exposureTimeNs = profile.exposureTimeNs,
    iso = profile.iso,
    targetFps = profile.targetFps,
    maxResolution = profile.maxResolution,
    syncThresholdMs = profile.syncThresholdMs,
    stabilization = profile.stabilization,
    focusMode = profile.focusMode,
    recordView = profile.recordView,
    recordFormat = profile.recordFormat,
    recordBitrateMbps = profile.recordBitrateMbps,
    imuRate = profile.imuRate,
    imuStreams = profile.imuStreams,
    cameraSource = profile.cameraSource,
    glassesImu = profile.glassesImu,
    glassesRectify = profile.glassesRectify,
    glassesManualExposure = profile.glassesManualExposure,
    glassesExposure100us = profile.glassesExposure100us,
    glassesGain = profile.glassesGain,
    oakMonoHeight = profile.oakMonoHeight,
    oakFps = profile.oakFps,
    oakRgbHeight = profile.oakRgbHeight,
    oakJpegQuality = profile.oakJpegQuality,
    oakImuRateHz = profile.oakImuRateHz,
    oakUsb3 = profile.oakUsb3,
    glassesImageFormat = profile.glassesImageFormat,
    oakImageFormat = profile.oakImageFormat,
    grayJpegQuality = profile.grayJpegQuality,
    videoBitrateMode = profile.videoBitrateMode,
    videoKeyframeIntervalS = profile.videoKeyframeIntervalS,
    videoProfile = profile.videoProfile,
    glassesVideoBitrateMbps = profile.glassesVideoBitrateMbps,
    oakVideoBitrateMbps = profile.oakVideoBitrateMbps,
)
