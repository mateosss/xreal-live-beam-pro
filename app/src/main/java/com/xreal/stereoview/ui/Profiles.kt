package com.xreal.stereoview.ui

import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.record.OakRgbFormat
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
                    if (oakRgbHeight > 0) {
                        "color ${oakRgbHeight}p " + (if (oakRgbFormat == OakRgbFormat.JPEG) "JPEG $oakJpegQuality" else oakRgbFormat.name) +
                            (if (oakRgbFps > 0 && oakRgbFps != oakFps) " $oakRgbFps fps" else "")
                    } else {
                        "no color"
                    },
                    if (oakImuRateHz >= OakDevice.MAX_GYRO_HZ) "OAK IMU max" else if (oakImuRateHz > 0) "OAK IMU $oakImuRateHz Hz" else "no OAK IMU",
                    OakDevice.Exposure.summary(oakExposures, oakRgbHeight > 0),
                ).joinToString(" · ")
            }
            if (cameraSource == CameraSource.GLASSES) {
                return@with listOf(
                    "glasses cameras 30 fps per eye, ${glassesImageFormat.name}",
                    if (glassesManualExposure) "exposure %.1f ms".format(glassesExposure100us / 10.0) else "auto exposure",
                    if (glassesRectify) "rectified stereo view" else "raw stereo view",
                    if (glassesImu) "glasses IMU taken over" else "glasses IMU left to the system",
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
                    Profile(PASSTHROUGH, base.copy(stabilization = Stabilization.OFF, previewStyle = PreviewStyle.PASSTHROUGH)),
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
                            oakMonoHeight = 400,
                            oakRgbHeight = 0,
                            oakImuRateHz = 0,
                            previewStyle = PreviewStyle.PASSTHROUGH,
                        ),
                    ),
                    Profile(
                        OAK_EUROC,
                        base.copy(
                            cameraSource = CameraSource.OAK,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FIT,
                            stabilization = Stabilization.OFF,
                            oakMonoHeight = 400,
                            oakFps = 30,
                            oakRgbHeight = 800,
                            oakJpegQuality = 95,
                            oakImuRateHz = OakDevice.MAX_GYRO_HZ,
                        ),
                    ),
                    Profile(
                        GLASSES_PASSTHROUGH,
                        base.copy(
                            cameraSource = CameraSource.GLASSES,
                            glassesImu = false,
                            convergencePx = 0f,
                            scaleMode = ScaleMode.FILL,
                            previewStyle = PreviewStyle.PASSTHROUGH,
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
                        ),
                    ),
                )
            }

        /**
         * Updates stored copies of built-in profiles whose defaults changed, if they still hold
         * one of their old defaults (never touched by the user): the glasses profiles now use
         * Fill (0.13), Passthrough has no stabilization (0.14); the OAK profiles stream the mono
         * pair at 640×400 and the dataset one the IMU at its maximum, without the phone's IMU (0.15);
         * the passthrough profiles show the passthrough-style portrait preview (0.15.1).
         */
        fun migrate(profiles: List<Profile>): List<Profile> {
            val old: Map<String, List<(StereoSettings) -> StereoSettings>> = mapOf(
                GLASSES_PASSTHROUGH to listOf(
                    { s -> s.copy(scaleMode = ScaleMode.REAL, previewStyle = PreviewStyle.CAPTURE) },
                    { s -> s.copy(previewStyle = PreviewStyle.CAPTURE) },
                ),
                PASSTHROUGH to listOf(
                    { s -> s.copy(stabilization = Stabilization.MEDIUM, previewStyle = PreviewStyle.CAPTURE) },
                    { s -> s.copy(previewStyle = PreviewStyle.CAPTURE) },
                ),
                OAK_PASSTHROUGH to listOf(
                    { s -> s.copy(oakMonoHeight = 720, oakImuRateHz = 0, previewStyle = PreviewStyle.CAPTURE) },
                    { s -> s.copy(previewStyle = PreviewStyle.CAPTURE) },
                ),
                OAK_EUROC to listOf({ s ->
                    s.copy(
                        oakMonoHeight = 800, oakImuRateHz = 200, imuRate = ImuRate.DIRECT_VERY_FAST,
                        imuStreams = PHONE_IMU_FOUR,
                    )
                }),
                GLASSES_EUROC to listOf(
                    { s -> s.copy(scaleMode = ScaleMode.FIT, imuRate = ImuRate.DIRECT_VERY_FAST, imuStreams = PHONE_IMU_FOUR) },
                    { s -> s.copy(imuRate = ImuRate.DIRECT_VERY_FAST, imuStreams = PHONE_IMU_FOUR) },
                ),
            )
            val base = StereoSettings()
            return profiles.map { p ->
                val olds = old[p.name] ?: return@map p
                val current = defaults.firstOrNull { it.name == p.name } ?: return@map p
                val stored = base.withProfile(p.settings)
                if (olds.any { stored == base.withProfile(it(current.settings)) }) current else p
            }
        }

        /** The phone IMU streams the dataset profiles of the other devices had before 0.15. */
        private const val PHONE_IMU_FOUR = "ACCELEROMETER,GYROSCOPE,ACCELEROMETER_UNCALIBRATED,GYROSCOPE_UNCALIBRATED"

        const val PASSTHROUGH = "Passthrough"
        const val EUROC = "EuRoC dataset"
        const val GLASSES_EUROC = "Glasses EuRoC dataset"
        const val GLASSES_PASSTHROUGH = "Glasses passthrough"
        const val OAK_PASSTHROUGH = "OAK passthrough"
        const val OAK_EUROC = "OAK EuRoC dataset"

        fun toJson(profiles: List<Profile>): String = JSONArray(
            profiles.map { p -> JSONObject().put("name", p.name).put("settings", SettingsSchema.toProfileJson(p.settings)) },
        ).toString()

        /** Parses [toJson]'s output; missing or unknown values take the defaults. */
        fun fromJson(text: String): List<Profile> {
            val array = JSONArray(text)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Profile(o.getString("name"), SettingsSchema.fromProfileJson(o.getJSONObject("settings")))
            }
        }
    }
}

/** These settings with every profile setting taken from [profile]. */
fun StereoSettings.withProfile(profile: StereoSettings) = SettingsSchema.withProfile(this, profile)
