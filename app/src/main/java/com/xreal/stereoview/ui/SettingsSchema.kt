package com.xreal.stereoview.ui

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xreal.stereoview.oak.OakDevice
import com.xreal.stereoview.record.BitrateMode
import com.xreal.stereoview.record.GrayImageFormat
import com.xreal.stereoview.record.H264Profile
import com.xreal.stereoview.record.OakRgbFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * One persisted field of [StereoSettings]: its [name] (the property, also its key in profile
 * JSON), its DataStore [key], whether profiles carry it, and how to read and write it.
 */
class Setting<T : Any, S : Any> internal constructor(
    val name: String,
    val key: Preferences.Key<S>,
    val inProfile: Boolean,
    private val codec: Codec<T, S>,
    private val get: (StereoSettings) -> T,
    private val set: (StereoSettings, T) -> StereoSettings,
) {
    /** [into] with this setting as stored in [p], if it is there and readable. */
    fun read(p: Preferences, into: StereoSettings): StereoSettings = p[key]?.let(codec.fromStore)?.let { set(into, it) } ?: into

    fun write(p: MutablePreferences, from: StereoSettings) {
        p[key] = codec.toStore(get(from))
    }

    fun toJson(o: JSONObject, from: StereoSettings) {
        o.put(name, codec.toJson(get(from)))
    }

    /** [into] with this setting from profile JSON [o], if it is there and readable. */
    fun fromJson(o: JSONObject, into: StereoSettings): StereoSettings =
        if (o.has(name)) runCatching { codec.fromJson(o, name) }.getOrNull()?.let { set(into, it) } ?: into else into

    /** [into] with this setting taken from [from]. */
    fun copy(from: StereoSettings, into: StereoSettings): StereoSettings = set(into, get(from))

    fun valueIn(s: StereoSettings): T = get(s)

    @Suppress("UNCHECKED_CAST")
    fun withValue(s: StereoSettings, value: Any): StereoSettings = set(s, value as T)
}

/** How a setting's value is stored in DataStore ([S]) and in profile JSON. */
class Codec<T : Any, S : Any>(
    val key: (String) -> Preferences.Key<S>,
    val toStore: (T) -> S,
    val fromStore: (S) -> T?,
    val toJson: (T) -> Any,
    /** May throw if the value has the wrong type: the setting then keeps its default. */
    val fromJson: (JSONObject, String) -> T?,
)

/**
 * Every persisted setting, declared once. DataStore ([read], [write]), profiles ([toProfileJson],
 * [fromProfileJson], [withProfile]) and their tests all go through this list, so a new
 * [StereoSettings] field only needs a line here (the tests fail until it has one). DataStore
 * keys are those of earlier versions: renaming one would lose the stored value.
 */
object SettingsSchema {
    private val FLOAT = Codec<Float, Float>(::floatPreferencesKey, { it }, { it }, { it.toDouble() }, { o, k -> o.getDouble(k).toFloat() })
    private val INT = Codec<Int, Int>(::intPreferencesKey, { it }, { it }, { it }, { o, k -> o.getInt(k) })
    private val LONG = Codec<Long, Long>(::longPreferencesKey, { it }, { it }, { it }, { o, k -> o.getLong(k) })
    private val BOOLEAN = Codec<Boolean, Boolean>(::booleanPreferencesKey, { it }, { it }, { it }, { o, k -> o.getBoolean(k) })
    private val STRING = Codec<String, String>(::stringPreferencesKey, { it }, { it }, { it }, { o, k -> o.get(k).takeIf { it != JSONObject.NULL }?.toString() })

    private inline fun <reified E : Enum<E>> enum(): Codec<E, String> {
        val values = enumValues<E>()
        return Codec(::stringPreferencesKey, { it.name }, { n -> values.firstOrNull { it.name == n } }, { it.name }, { o, k -> o.getString(k).let { n -> values.firstOrNull { it.name == n } } })
    }

    private val EXPOSURES = Codec<List<OakDevice.Exposure>, String>(
        ::stringPreferencesKey, OakDevice.Exposure::listToJson, OakDevice.Exposure::listFromJson,
        { JSONArray(OakDevice.Exposure.listToJson(it)) }, { o, k -> OakDevice.Exposure.listFromJson(o.getJSONArray(k).toString()) },
    )

    private fun <T : Any, S : Any> profile(name: String, key: String, codec: Codec<T, S>, get: (StereoSettings) -> T, set: (StereoSettings, T) -> StereoSettings) =
        Setting(name, codec.key(key), true, codec, get, set)

    private fun <T : Any, S : Any> device(name: String, key: String, codec: Codec<T, S>, get: (StereoSettings) -> T, set: (StereoSettings, T) -> StereoSettings) =
        Setting(name, codec.key(key), false, codec, get, set)

    private fun buffer(name: String, key: String, get: (BufferSettings) -> Int, set: (BufferSettings, Int) -> BufferSettings) =
        device("buffers.$name", key, INT, { get(it.buffers) }, { s, v -> s.copy(buffers = set(s.buffers, v)) })

    private val oakExposures = profile("oakExposures", "oak_exposures", EXPOSURES, { it.oakExposures }, { s, v -> s.copy(oakExposures = v) })

    val all: List<Setting<*, *>> = listOf(
        // Profile settings: how to view and record.
        profile("convergencePx", "convergence_px", FLOAT, { it.convergencePx }, { s, v -> s.copy(convergencePx = v) }),
        profile("depth", "depth", FLOAT, { it.depth }, { s, v -> s.copy(depth = v) }),
        profile("scaleMode", "scale_mode", enum<ScaleMode>(), { it.scaleMode }, { s, v -> s.copy(scaleMode = v) }),
        profile("previewStyle", "preview_style", enum<PreviewStyle>(), { it.previewStyle }, { s, v -> s.copy(previewStyle = v) }),
        profile("exposure", "exposure", INT, { it.exposure }, { s, v -> s.copy(exposure = v) }),
        profile("manualExposure", "manual_exposure", BOOLEAN, { it.manualExposure }, { s, v -> s.copy(manualExposure = v) }),
        profile("exposureTimeNs", "exposure_time_ns", LONG, { it.exposureTimeNs }, { s, v -> s.copy(exposureTimeNs = v) }),
        profile("iso", "iso", INT, { it.iso }, { s, v -> s.copy(iso = v) }),
        profile("targetFps", "target_fps", INT, { it.targetFps }, { s, v -> s.copy(targetFps = v) }),
        profile("maxResolution", "max_resolution", INT, { it.maxResolution }, { s, v -> s.copy(maxResolution = v) }),
        profile("syncThresholdMs", "sync_threshold_ms", INT, { it.syncThresholdMs }, { s, v -> s.copy(syncThresholdMs = v) }),
        profile("stabilization", "stabilization", enum<Stabilization>(), { it.stabilization }, { s, v -> s.copy(stabilization = v) }),
        profile("focusMode", "focus_mode", enum<FocusMode>(), { it.focusMode }, { s, v -> s.copy(focusMode = v) }),
        profile("recordView", "record_view", enum<RecordView>(), { it.recordView }, { s, v -> s.copy(recordView = v) }),
        profile("recordFormat", "record_format", enum<RecordFormat>(), { it.recordFormat }, { s, v -> s.copy(recordFormat = v) }),
        profile("recordBitrateMbps", "record_bitrate_mbps", INT, { it.recordBitrateMbps }, { s, v -> s.copy(recordBitrateMbps = v) }),
        profile("imuRate", "imu_rate", enum<ImuRate>(), { it.imuRate }, { s, v -> s.copy(imuRate = v) }),
        profile("imuStreams", "imu_streams", STRING, { it.imuStreams }, { s, v -> s.copy(imuStreams = v) }),
        profile("cameraSource", "camera_source", enum<CameraSource>(), { it.cameraSource }, { s, v -> s.copy(cameraSource = v) }),
        profile("glassesImu", "glasses_imu", BOOLEAN, { it.glassesImu }, { s, v -> s.copy(glassesImu = v) }),
        profile("glassesRectify", "glasses_rectify", BOOLEAN, { it.glassesRectify }, { s, v -> s.copy(glassesRectify = v) }),
        profile("glassesManualExposure", "glasses_manual_exposure", BOOLEAN, { it.glassesManualExposure }, { s, v -> s.copy(glassesManualExposure = v) }),
        profile("glassesExposure100us", "glasses_exposure_100us", INT, { it.glassesExposure100us }, { s, v -> s.copy(glassesExposure100us = v) }),
        profile("glassesGain", "glasses_gain", INT, { it.glassesGain }, { s, v -> s.copy(glassesGain = v) }),
        profile("glassesImageFormat", "glasses_image_format", enum<GrayImageFormat>(), { it.glassesImageFormat }, { s, v -> s.copy(glassesImageFormat = v) }),
        profile("glassesVideoBitrateMbps", "glasses_video_bitrate_mbps", INT, { it.glassesVideoBitrateMbps }, { s, v -> s.copy(glassesVideoBitrateMbps = v) }),
        profile("oakMonoHeight", "oak_mono_height", INT, { it.oakMonoHeight }, { s, v -> s.copy(oakMonoHeight = v) }),
        profile("oakFps", "oak_fps", INT, { it.oakFps }, { s, v -> s.copy(oakFps = v) }),
        profile("oakRgbFps", "oak_rgb_fps", INT, { it.oakRgbFps }, { s, v -> s.copy(oakRgbFps = v) }),
        profile("oakRgbHeight", "oak_rgb_height", INT, { it.oakRgbHeight }, { s, v -> s.copy(oakRgbHeight = v) }),
        profile("oakJpegQuality", "oak_jpeg_quality", INT, { it.oakJpegQuality }, { s, v -> s.copy(oakJpegQuality = v) }),
        profile("oakImuRateHz", "oak_imu_rate_hz", INT, { it.oakImuRateHz }, { s, v -> s.copy(oakImuRateHz = v) }),
        profile("oakUsb3", "oak_usb3", BOOLEAN, { it.oakUsb3 }, { s, v -> s.copy(oakUsb3 = v) }),
        profile("oakRgbFormat", "oak_rgb_format", enum<OakRgbFormat>(), { it.oakRgbFormat }, { s, v -> s.copy(oakRgbFormat = v) }),
        profile("oakRgbVideoBitrateMbps", "oak_rgb_video_bitrate_mbps", INT, { it.oakRgbVideoBitrateMbps }, { s, v -> s.copy(oakRgbVideoBitrateMbps = v) }),
        oakExposures,
        profile("oakImageFormat", "oak_image_format", enum<GrayImageFormat>(), { it.oakImageFormat }, { s, v -> s.copy(oakImageFormat = v) }),
        profile("oakVideoBitrateMbps", "oak_video_bitrate_mbps", INT, { it.oakVideoBitrateMbps }, { s, v -> s.copy(oakVideoBitrateMbps = v) }),
        profile("grayJpegQuality", "gray_jpeg_quality", INT, { it.grayJpegQuality }, { s, v -> s.copy(grayJpegQuality = v) }),
        profile("videoBitrateMode", "video_bitrate_mode", enum<BitrateMode>(), { it.videoBitrateMode }, { s, v -> s.copy(videoBitrateMode = v) }),
        profile("videoKeyframeIntervalS", "video_keyframe_interval_s", FLOAT, { it.videoKeyframeIntervalS }, { s, v -> s.copy(videoKeyframeIntervalS = v) }),
        profile("videoProfile", "video_profile", enum<H264Profile>(), { it.videoProfile }, { s, v -> s.copy(videoProfile = v) }),
        profile("videoSoftwareEncoder", "video_software_encoder", BOOLEAN, { it.videoSoftwareEncoder }, { s, v -> s.copy(videoSoftwareEncoder = v) }),

        // Device setup: kept across profiles, so switching profiles never undoes calibration.
        device("swapEyes", "swap_eyes", BOOLEAN, { it.swapEyes }, { s, v -> s.copy(swapEyes = v) }),
        device("portraitScaleMode", "portrait_scale_mode", enum<ScaleMode>(), { it.portraitScaleMode }, { s, v -> s.copy(portraitScaleMode = v) }),
        device("showHud", "show_hud", BOOLEAN, { it.showHud }, { s, v -> s.copy(showHud = v) }),
        device("rotationOverride", "rotation_override", INT, { it.rotationOverride }, { s, v -> s.copy(rotationOverride = v) }),
        device("outputMode", "output_mode", enum<OutputMode>(), { it.outputMode }, { s, v -> s.copy(outputMode = v) }),
        device("cameraPair", "camera_pair", STRING, { it.cameraPair }, { s, v -> s.copy(cameraPair = v) }),
        device("glassesFovDeg", "glasses_fov_deg", FLOAT, { it.glassesFovDeg }, { s, v -> s.copy(glassesFovDeg = v) }),
        device("sbsLayout", "sbs_layout", enum<SbsLayout>(), { it.sbsLayout }, { s, v -> s.copy(sbsLayout = v) }),
        device("useFactoryCalibration", "use_factory_calibration", BOOLEAN, { it.useFactoryCalibration }, { s, v -> s.copy(useFactoryCalibration = v) }),
        device("beamProAlignPitchDeg", "align_pitch_deg", FLOAT, { it.beamProAlignPitchDeg }, { s, v -> s.copy(beamProAlignPitchDeg = v) }),
        device("beamProAlignRollDeg", "align_roll_deg", FLOAT, { it.beamProAlignRollDeg }, { s, v -> s.copy(beamProAlignRollDeg = v) }),
        device("glassesAlignPitchDeg", "glasses_align_pitch_deg", FLOAT, { it.glassesAlignPitchDeg }, { s, v -> s.copy(glassesAlignPitchDeg = v) }),
        device("glassesAlignRollDeg", "glasses_align_roll_deg", FLOAT, { it.glassesAlignRollDeg }, { s, v -> s.copy(glassesAlignRollDeg = v) }),
        device("oakAlignPitchDeg", "oak_align_pitch_deg", FLOAT, { it.oakAlignPitchDeg }, { s, v -> s.copy(oakAlignPitchDeg = v) }),
        device("oakAlignRollDeg", "oak_align_roll_deg", FLOAT, { it.oakAlignRollDeg }, { s, v -> s.copy(oakAlignRollDeg = v) }),
        buffer("glassesCameraS", "buffer_glasses_camera_s", { it.glassesCameraS }, { b, v -> b.copy(glassesCameraS = v) }),
        buffer("glassesImuS", "buffer_glasses_imu_s", { it.glassesImuS }, { b, v -> b.copy(glassesImuS = v) }),
        buffer("oakCameraS", "buffer_oak_camera_s", { it.oakCameraS }, { b, v -> b.copy(oakCameraS = v) }),
        buffer("oakLinkS", "buffer_oak_link_s", { it.oakLinkS }, { b, v -> b.copy(oakLinkS = v) }),
        buffer("oakImuS", "buffer_oak_imu_s", { it.oakImuS }, { b, v -> b.copy(oakImuS = v) }),
        buffer("phoneCameraS", "buffer_phone_camera_s", { it.phoneCameraS }, { b, v -> b.copy(phoneCameraS = v) }),
    )

    val profileSettings: List<Setting<*, *>> = all.filter { it.inProfile }

    /** The settings stored in [p]; missing or unreadable ones take their defaults. */
    fun read(p: Preferences): StereoSettings {
        val s = all.fold(StereoSettings()) { s, f -> f.read(p, s) }
        // Before 0.16 the OAK took the shared exposure settings, for all cameras.
        return if (p[oakExposures.key]?.let(OakDevice.Exposure::listFromJson) == null) s.withLegacyOakExposures() else s
    }

    fun write(p: MutablePreferences, s: StereoSettings) = all.forEach { it.write(p, s) }

    fun toProfileJson(s: StereoSettings): JSONObject = JSONObject().also { o -> profileSettings.forEach { it.toJson(o, s) } }

    /** Profile settings from [o] over the defaults; missing or unreadable ones take their defaults. */
    fun fromProfileJson(o: JSONObject): StereoSettings {
        val s = profileSettings.fold(StereoSettings()) { s, f -> f.fromJson(o, s) }
        // Before 0.16 the OAK took the profile's shared exposure settings.
        val stored = runCatching { OakDevice.Exposure.listFromJson(o.getJSONArray(oakExposures.name).toString()) }.getOrNull()
        return if (stored == null) s.withLegacyOakExposures() else s
    }

    /** [base] with every profile setting taken from [profile]. */
    fun withProfile(base: StereoSettings, profile: StereoSettings): StereoSettings = profileSettings.fold(base) { s, f -> f.copy(profile, s) }

    private fun StereoSettings.withLegacyOakExposures() = copy(oakExposures = StereoSettings.legacyOakExposures(manualExposure, exposureTimeNs, iso, exposure))
}
