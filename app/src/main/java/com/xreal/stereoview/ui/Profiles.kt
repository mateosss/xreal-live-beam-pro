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
                    Profile(PASSTHROUGH, base),
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
                )
            }

        const val PASSTHROUGH = "Passthrough"
        const val EUROC = "EuRoC dataset"

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
)
