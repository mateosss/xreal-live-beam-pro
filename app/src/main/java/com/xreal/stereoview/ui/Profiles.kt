package com.xreal.stereoview.ui

import com.xreal.stereoview.R
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

    /** The built-in profile of this name, if this is one (by name: renaming makes it a custom profile). */
    val builtIn: Profile? get() = defaults.firstOrNull { it.name == name }

    /** A built-in profile whose settings (those its device uses) differ from the original. */
    val isModifiedBuiltIn: Boolean
        get() {
            val original = builtIn ?: return false
            return SettingsSchema.profileSettingsFor(settings.cameraSource).any { it.valueIn(settings) != it.valueIn(original.settings) }
        }

    /** What the profile does, for lists (short, with icons) and its details (long). */
    val facts: List<Fact>
        get() = with(settings) {
            when (cameraSource) {
                CameraSource.OAK -> listOf(
                    Fact(R.drawable.ic_resolution, "${oakMonoHeight}p", "Mono cameras at ${oakMonoHeight}p"),
                    Fact(R.drawable.ic_speed, "$oakFps fps", "$oakFps fps"),
                    Fact(R.drawable.ic_image, oakImageFormat.name, "Mono images recorded as ${oakImageFormat.label}"),
                    if (oakRgbHeight > 0) {
                        val format = if (oakRgbFormat == OakRgbFormat.JPEG) "JPEG $oakJpegQuality" else oakRgbFormat.name
                        val fps = if (oakRgbFps > 0f && oakRgbFps != oakFps.toFloat()) " ${BitrateScale.number(oakRgbFps)} fps" else ""
                        Fact(R.drawable.ic_palette, "${oakRgbHeight}p $format$fps", "Color camera at ${oakRgbHeight}p, $format$fps")
                    } else {
                        Fact(R.drawable.ic_palette, "off", "No color camera", on = false)
                    },
                    when {
                        oakImuRateHz >= OakDevice.MAX_GYRO_HZ -> Fact(R.drawable.ic_imu, "max", "OAK IMU at its maximum rate")
                        oakImuRateHz > 0 -> Fact(R.drawable.ic_imu, "$oakImuRateHz Hz", "OAK IMU at $oakImuRateHz Hz")
                        else -> Fact(R.drawable.ic_imu, "off", "No OAK IMU", on = false)
                    },
                    Fact(R.drawable.ic_exposure, oakExposureShort(), OakDevice.Exposure.summary(oakExposures, oakRgbHeight > 0).replaceFirstChar { it.uppercase() }),
                )
                CameraSource.GLASSES -> listOf(
                    Fact(R.drawable.ic_speed, "30 fps", "Glasses cameras at 30 fps per eye"),
                    Fact(R.drawable.ic_image, glassesImageFormat.name, "Recorded as ${glassesImageFormat.label}"),
                    if (glassesManualExposure) {
                        Fact(R.drawable.ic_exposure, "%.1f ms".format(glassesExposure100us / 10.0), "Manual exposure %.1f ms, gain $glassesGain".format(glassesExposure100us / 10.0))
                    } else {
                        Fact(R.drawable.ic_exposure, "auto", "Auto exposure")
                    },
                    Fact(R.drawable.ic_grid, if (glassesRectify) "rectified" else "raw", if (glassesRectify) "Rectified stereo view" else "Raw stereo view"),
                    if (glassesImu) Fact(R.drawable.ic_imu, "IMU", "Glasses IMU taken over and recorded") else Fact(R.drawable.ic_imu, "off", "Glasses IMU left to the system", on = false),
                )
                CameraSource.BEAM_PRO -> listOf(
                    Fact(R.drawable.ic_resolution, "≤${maxResolution}p", "Stream up to ${maxResolution}p"),
                    Fact(R.drawable.ic_speed, "$targetFps fps", "$targetFps fps"),
                    if (manualExposure) {
                        Fact(R.drawable.ic_exposure, "1/${(1e9 / exposureTimeNs).toInt()} ISO $iso", "Manual exposure 1/${(1e9 / exposureTimeNs).toInt()} s, ISO $iso")
                    } else {
                        Fact(R.drawable.ic_exposure, "auto", "Auto exposure")
                    },
                    Fact(R.drawable.ic_focus, if (focusMode == FocusMode.INFINITY) "∞" else "AF", focusMode.label),
                    Fact(R.drawable.ic_stabilization, stabilization.label.lowercase(), "Stabilization ${stabilization.label.lowercase()}", on = stabilization != Stabilization.OFF),
                    Fact(R.drawable.ic_videocam, "${recordView.name.lowercase()} ${recordFormat.name}", "Records ${recordView.label.substringBefore(":").lowercase()} views as ${recordFormat.label}"),
                    Fact(R.drawable.ic_imu, imuRateShort(), "Phone IMU: ${imuRate.label}"),
                )
            }
        }

    private fun StereoSettings.oakExposureShort(): String {
        val used = if (oakRgbHeight > 0) oakExposures else oakExposures.take(2)
        if (used.distinct().size > 1) return "per camera"
        val e = used[0]
        return if (e.auto) "auto" + (if (e.compensation != 0) " %+d".format(e.compensation) else "") else "${e.exposureUs} µs"
    }

    private fun StereoSettings.imuRateShort(): String =
        if (imuRate == ImuRate.FASTEST) "max" else imuRate.label.substringBefore(" (").substringAfter(", ")

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
                            recordBitrateMbps = 60f,
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
