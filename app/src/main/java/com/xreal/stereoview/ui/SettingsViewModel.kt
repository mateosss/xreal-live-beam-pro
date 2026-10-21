package com.xreal.stereoview.ui

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How each camera image is fitted into its half of the SBS frame. */
enum class ScaleMode(val label: String) {
    REAL("1:1"),        // camera angles shown at their true size in the glasses
    FILL("Fill"),       // keep aspect, crop overflow
    FIT("Fit"),         // keep aspect, letterbox
    STRETCH("Stretch"), // ignore aspect (useful for half-SBS output)
}

/** How the glasses split the frame into eyes. */
enum class SbsLayout(val label: String) {
    AUTO("Auto (from output size)"),
    FULL("Full SBS: each half shown at its own aspect"),
    HALF("Half SBS: each half stretched to double width"),
}

/** Gyro stabilization strength, as the smoothing time constant. */
enum class Stabilization(val label: String, val timeConstantSec: Double) {
    OFF("Off", 0.0),
    LOW("Low", 0.12),
    MEDIUM("Medium", 0.3),
    HIGH("High", 0.8),
}

/** Camera focus: continuous autofocus, or locked at infinity so the intrinsics stay fixed. */
enum class FocusMode(val label: String) {
    AUTO("Continuous autofocus"),
    INFINITY("Locked at infinity (fixed intrinsics, for SLAM)"),
}

/** Where the stereo images come from. Only one source runs at a time. */
enum class CameraSource(val label: String) {
    BEAM_PRO("Beam Pro rear cameras"),
    GLASSES("Glasses tracking cameras (Air 2 Ultra, over USB)"),
}

/** Which image a recording stores per camera. */
enum class RecordView(val label: String) {
    RAW("Raw: camera images as streamed (distorted, unrotated)"),
    RECTIFIED("Rectified: what each eye sees (undistorted, aligned)"),
}

/** How recorded frames are encoded. */
enum class RecordFormat(val label: String, val mime: String?) {
    H264("H.264 video", "video/avc"),
    HEVC("H.265 video", "video/hevc"),
    JPEG("JPEG images (one file per frame, may drop frames)", null),
    PNG("PNG grayscale images, as EuRoC (lossless, may drop frames)", null),
}

/**
 * IMU sampling: [periodUs] for a regular listener (0 = as fast as the sensor allows), or a
 * [directRate] level (SensorDirectChannel.RATE_*) to read through a shared-memory direct channel.
 */
enum class ImuRate(val label: String, val periodUs: Int, val directRate: Int = 0) {
    FASTEST("Fastest the sensors allow (listener)", 0),
    HZ_500("500 Hz (listener)", 2_000),
    HZ_200("200 Hz (listener)", 5_000),
    HZ_100("100 Hz (listener)", 10_000),
    DIRECT_VERY_FAST("Direct channel, ~800 Hz", 0, 3),
    DIRECT_FAST("Direct channel, ~200 Hz", 0, 2),
}

/** Where the SBS frame is rendered. */
enum class OutputMode(val label: String) {
    AUTO("Glasses display if detected"),
    MAIN_SCREEN("This screen (mirrored)"),
}

data class StereoSettings(
    /** Horizontal image shift per eye in render pixels, ±[CONVERGENCE_RANGE]. */
    val convergencePx: Float = -50f,
    /** Magnification about each eye's center, 1..2. Scales disparity along with the image. */
    val depth: Float = 1f,
    val swapEyes: Boolean = true,
    /** Exposure compensation in camera steps (see CONTROL_AE_COMPENSATION_RANGE). */
    val exposure: Int = 0,
    val scaleMode: ScaleMode = ScaleMode.REAL,
    val showHud: Boolean = false,
    /** Image rotation in degrees, or -1 to derive it from sensor and display orientation. */
    val rotationOverride: Int = 270,
    /** How long to wait for the partner eye's frame before drawing anyway. */
    val syncThresholdMs: Int = 33,
    /** Maximum stream height; the largest shared size up to this is used. */
    val maxResolution: Int = 1080,
    val outputMode: OutputMode = OutputMode.AUTO,
    /** [com.xreal.stereoview.camera.StereoRig.key] of a manually chosen camera pair, or empty for automatic. */
    val cameraPair: String = "",
    /** Diagonal field of view of one eye of the glasses, used by [ScaleMode.REAL]. */
    val glassesFovDeg: Float = 46f,
    val sbsLayout: SbsLayout = SbsLayout.AUTO,
    val stabilization: Stabilization = Stabilization.MEDIUM,
    /** Use the lens poses reported by the camera; otherwise only the sensor orientation. */
    val useFactoryCalibration: Boolean = true,
    /** Vertical alignment correction between the eyes, degrees. */
    val alignPitchDeg: Float = 0f,
    /** Rotational alignment correction between the eyes, degrees. */
    val alignRollDeg: Float = 0f,
    /** Requested camera frame rate; stream sizes that can't sustain it are skipped. */
    val targetFps: Int = 30,
    /** Fixed exposure time and ISO instead of auto exposure. */
    val manualExposure: Boolean = false,
    val exposureTimeNs: Long = 4_000_000L,
    val iso: Int = 400,
    val focusMode: FocusMode = FocusMode.AUTO,
    val recordView: RecordView = RecordView.RAW,
    val recordFormat: RecordFormat = RecordFormat.H264,
    val recordBitrateMbps: Int = 40,
    val imuRate: ImuRate = ImuRate.FASTEST,
    /** Comma-separated [com.xreal.stereoview.record.ImuStream] names to record. */
    val imuStreams: String = "ACCELEROMETER,GYROSCOPE,ACCELEROMETER_UNCALIBRATED,GYROSCOPE_UNCALIBRATED,GAME_ROTATION_VECTOR",
    val cameraSource: CameraSource = CameraSource.BEAM_PRO,
    /** With the glasses cameras: also take over the glasses' IMU (stops XREAL's head tracking until replugged). */
    val glassesImu: Boolean = false,
    /** Show the glasses cameras undistorted and rectified (preview only; recordings stay raw). */
    val glassesRectify: Boolean = true,
    /** Glasses cameras: fixed exposure time and gain instead of their auto exposure (if they offer it). */
    val glassesManualExposure: Boolean = false,
    /** Glasses exposure time in UVC units of 100 µs. */
    val glassesExposure100us: Int = 50,
    /** Glasses camera gain, or -1 for the camera's default. */
    val glassesGain: Int = -1,
) {
    companion object {
        const val CONVERGENCE_RANGE = 200f
        const val ALIGN_RANGE_DEG = 3f
        const val DEPTH_MIN = 1f
        const val DEPTH_MAX = 2f
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "stereo_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val CONVERGENCE = floatPreferencesKey("convergence_px")
        val DEPTH = floatPreferencesKey("depth")
        val SWAP = booleanPreferencesKey("swap_eyes")
        val EXPOSURE = intPreferencesKey("exposure")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val HUD = booleanPreferencesKey("show_hud")
        val ROTATION = intPreferencesKey("rotation_override")
        val SYNC_THRESHOLD = intPreferencesKey("sync_threshold_ms")
        val MAX_RESOLUTION = intPreferencesKey("max_resolution")
        val OUTPUT_MODE = stringPreferencesKey("output_mode")
        val CAMERA_PAIR = stringPreferencesKey("camera_pair")
        val GLASSES_FOV = floatPreferencesKey("glasses_fov_deg")
        val SBS_LAYOUT = stringPreferencesKey("sbs_layout")
        val STABILIZATION = stringPreferencesKey("stabilization")
        val FACTORY_CALIBRATION = booleanPreferencesKey("use_factory_calibration")
        val ALIGN_PITCH = floatPreferencesKey("align_pitch_deg")
        val ALIGN_ROLL = floatPreferencesKey("align_roll_deg")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val MANUAL_EXPOSURE = booleanPreferencesKey("manual_exposure")
        val EXPOSURE_TIME = longPreferencesKey("exposure_time_ns")
        val ISO = intPreferencesKey("iso")
        val FOCUS_MODE = stringPreferencesKey("focus_mode")
        val RECORD_VIEW = stringPreferencesKey("record_view")
        val RECORD_FORMAT = stringPreferencesKey("record_format")
        val RECORD_BITRATE = intPreferencesKey("record_bitrate_mbps")
        val IMU_RATE = stringPreferencesKey("imu_rate")
        val IMU_STREAMS = stringPreferencesKey("imu_streams")
        val CAMERA_SOURCE = stringPreferencesKey("camera_source")
        val GLASSES_IMU = booleanPreferencesKey("glasses_imu")
        val GLASSES_RECTIFY = booleanPreferencesKey("glasses_rectify")
        val GLASSES_MANUAL_EXPOSURE = booleanPreferencesKey("glasses_manual_exposure")
        val GLASSES_EXPOSURE = intPreferencesKey("glasses_exposure_100us")
        val GLASSES_GAIN = intPreferencesKey("glasses_gain")
        val PROFILES = stringPreferencesKey("profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val BUILTINS_SEEN = stringPreferencesKey("builtin_profiles_seen")
    }

    /** Saved profiles (the built-in ones until anything is saved) and the active one's name. */
    /** Built-in profiles added in later versions are appended once; deleted ones stay deleted. */
    val profiles = context.dataStore.data.map { p ->
        val stored = p[Keys.PROFILES]?.let { runCatching { Profile.fromJson(it) }.getOrNull() }
        val seen = p[Keys.BUILTINS_SEEN]?.split('\n')?.toSet()
            ?: if (stored != null) setOf(Profile.PASSTHROUGH, Profile.EUROC) else emptySet()
        val list = (stored ?: emptyList()) + Profile.defaults.filter { d -> d.name !in seen && stored?.none { it.name == d.name } != false }
        ProfileState(list, p[Keys.ACTIVE_PROFILE] ?: Profile.PASSTHROUGH)
    }

    suspend fun saveProfiles(state: ProfileState) {
        context.dataStore.edit { p ->
            p[Keys.PROFILES] = Profile.toJson(state.profiles)
            p[Keys.ACTIVE_PROFILE] = state.active
            p[Keys.BUILTINS_SEEN] = Profile.defaults.joinToString("\n") { it.name }
        }
    }

    val settings = context.dataStore.data.map { p ->
        val defaults = StereoSettings()
        StereoSettings(
            convergencePx = p[Keys.CONVERGENCE] ?: defaults.convergencePx,
            depth = p[Keys.DEPTH] ?: defaults.depth,
            swapEyes = p[Keys.SWAP] ?: defaults.swapEyes,
            exposure = p[Keys.EXPOSURE] ?: defaults.exposure,
            scaleMode = enumOrDefault(p[Keys.SCALE_MODE], defaults.scaleMode),
            showHud = p[Keys.HUD] ?: defaults.showHud,
            rotationOverride = p[Keys.ROTATION] ?: defaults.rotationOverride,
            syncThresholdMs = p[Keys.SYNC_THRESHOLD] ?: defaults.syncThresholdMs,
            maxResolution = p[Keys.MAX_RESOLUTION] ?: defaults.maxResolution,
            outputMode = enumOrDefault(p[Keys.OUTPUT_MODE], defaults.outputMode),
            cameraPair = p[Keys.CAMERA_PAIR] ?: defaults.cameraPair,
            glassesFovDeg = p[Keys.GLASSES_FOV] ?: defaults.glassesFovDeg,
            sbsLayout = enumOrDefault(p[Keys.SBS_LAYOUT], defaults.sbsLayout),
            stabilization = enumOrDefault(p[Keys.STABILIZATION], defaults.stabilization),
            useFactoryCalibration = p[Keys.FACTORY_CALIBRATION] ?: defaults.useFactoryCalibration,
            alignPitchDeg = p[Keys.ALIGN_PITCH] ?: defaults.alignPitchDeg,
            alignRollDeg = p[Keys.ALIGN_ROLL] ?: defaults.alignRollDeg,
            targetFps = p[Keys.TARGET_FPS] ?: defaults.targetFps,
            manualExposure = p[Keys.MANUAL_EXPOSURE] ?: defaults.manualExposure,
            exposureTimeNs = p[Keys.EXPOSURE_TIME] ?: defaults.exposureTimeNs,
            iso = p[Keys.ISO] ?: defaults.iso,
            focusMode = enumOrDefault(p[Keys.FOCUS_MODE], defaults.focusMode),
            recordView = enumOrDefault(p[Keys.RECORD_VIEW], defaults.recordView),
            recordFormat = enumOrDefault(p[Keys.RECORD_FORMAT], defaults.recordFormat),
            recordBitrateMbps = p[Keys.RECORD_BITRATE] ?: defaults.recordBitrateMbps,
            imuRate = enumOrDefault(p[Keys.IMU_RATE], defaults.imuRate),
            imuStreams = p[Keys.IMU_STREAMS] ?: defaults.imuStreams,
            cameraSource = enumOrDefault(p[Keys.CAMERA_SOURCE], defaults.cameraSource),
            glassesImu = p[Keys.GLASSES_IMU] ?: defaults.glassesImu,
            glassesRectify = p[Keys.GLASSES_RECTIFY] ?: defaults.glassesRectify,
            glassesManualExposure = p[Keys.GLASSES_MANUAL_EXPOSURE] ?: defaults.glassesManualExposure,
            glassesExposure100us = p[Keys.GLASSES_EXPOSURE] ?: defaults.glassesExposure100us,
            glassesGain = p[Keys.GLASSES_GAIN] ?: defaults.glassesGain,
        )
    }

    suspend fun save(s: StereoSettings) {
        context.dataStore.edit { p ->
            p[Keys.CONVERGENCE] = s.convergencePx
            p[Keys.DEPTH] = s.depth
            p[Keys.SWAP] = s.swapEyes
            p[Keys.EXPOSURE] = s.exposure
            p[Keys.SCALE_MODE] = s.scaleMode.name
            p[Keys.HUD] = s.showHud
            p[Keys.ROTATION] = s.rotationOverride
            p[Keys.SYNC_THRESHOLD] = s.syncThresholdMs
            p[Keys.MAX_RESOLUTION] = s.maxResolution
            p[Keys.OUTPUT_MODE] = s.outputMode.name
            p[Keys.CAMERA_PAIR] = s.cameraPair
            p[Keys.GLASSES_FOV] = s.glassesFovDeg
            p[Keys.SBS_LAYOUT] = s.sbsLayout.name
            p[Keys.STABILIZATION] = s.stabilization.name
            p[Keys.FACTORY_CALIBRATION] = s.useFactoryCalibration
            p[Keys.ALIGN_PITCH] = s.alignPitchDeg
            p[Keys.ALIGN_ROLL] = s.alignRollDeg
            p[Keys.TARGET_FPS] = s.targetFps
            p[Keys.MANUAL_EXPOSURE] = s.manualExposure
            p[Keys.EXPOSURE_TIME] = s.exposureTimeNs
            p[Keys.ISO] = s.iso
            p[Keys.FOCUS_MODE] = s.focusMode.name
            p[Keys.RECORD_VIEW] = s.recordView.name
            p[Keys.RECORD_FORMAT] = s.recordFormat.name
            p[Keys.RECORD_BITRATE] = s.recordBitrateMbps
            p[Keys.IMU_RATE] = s.imuRate.name
            p[Keys.IMU_STREAMS] = s.imuStreams
            p[Keys.CAMERA_SOURCE] = s.cameraSource.name
            p[Keys.GLASSES_IMU] = s.glassesImu
            p[Keys.GLASSES_RECTIFY] = s.glassesRectify
            p[Keys.GLASSES_MANUAL_EXPOSURE] = s.glassesManualExposure
            p[Keys.GLASSES_EXPOSURE] = s.glassesExposure100us
            p[Keys.GLASSES_GAIN] = s.glassesGain
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

/**
 * Holds the live settings in memory (so sliders react instantly) and persists every change
 * to DataStore in the background.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val _settings = MutableStateFlow(StereoSettings())
    val settings: StateFlow<StereoSettings> = _settings.asStateFlow()
    private val _profiles = MutableStateFlow(ProfileState(Profile.defaults, Profile.PASSTHROUGH))
    val profiles: StateFlow<ProfileState> = _profiles.asStateFlow()
    /** Whether the stored settings are in; until then [settings] holds the defaults. */
    var loaded = false
        private set

    init {
        viewModelScope.launch {
            _profiles.value = repository.profiles.first()
            val stored = repository.settings.first()
            loaded = true
            _settings.value = stored
        }
    }

    /** Switches to profile [name], replacing every profile setting. */
    fun selectProfile(name: String) {
        val profile = _profiles.value.find(name) ?: return
        update { it.withProfile(profile.settings) }
        setProfiles(_profiles.value.copy(active = name))
    }

    /** Stores the current settings as profile [name] (new or overwritten) and makes it active. */
    fun saveProfile(name: String) {
        val state = _profiles.value
        val profile = Profile(name, _settings.value)
        val list = if (state.find(name) != null) state.profiles.map { if (it.name == name) profile else it } else state.profiles + profile
        setProfiles(ProfileState(list, name))
    }

    fun renameProfile(from: String, to: String) {
        val state = _profiles.value
        if (to.isBlank() || state.find(to) != null) return
        setProfiles(ProfileState(
            state.profiles.map { if (it.name == from) it.copy(name = to) else it },
            if (state.active == from) to else state.active,
        ))
    }

    /** Adds a copy of profile [from] named [to], after it in the list. */
    fun duplicateProfile(from: String, to: String) {
        val state = _profiles.value
        val source = state.find(from) ?: return
        if (state.find(to) != null) return
        val list = state.profiles.toMutableList().apply { add(indexOf(source) + 1, source.copy(name = to)) }
        setProfiles(state.copy(profiles = list))
    }

    fun deleteProfile(name: String) {
        val state = _profiles.value
        setProfiles(ProfileState(state.profiles.filter { it.name != name }, if (state.active == name) "" else state.active))
    }

    /** Resets the built-in profiles (re-adding deleted ones) and keeps custom ones. */
    fun restoreDefaultProfiles() {
        val state = _profiles.value
        val defaults = Profile.defaults
        val custom = state.profiles.filter { p -> defaults.none { it.name == p.name } }
        setProfiles(ProfileState(defaults + custom, state.active))
        state.find(state.active)?.let { selectProfile(it.name) }
    }

    private fun setProfiles(state: ProfileState) {
        if (!loaded) return
        _profiles.value = state
        viewModelScope.launch { repository.saveProfiles(state) }
    }

    fun update(transform: (StereoSettings) -> StereoSettings) {
        // Ignore edits until the stored values are in, so defaults never overwrite them.
        if (!loaded) return
        _settings.update(transform)
        val snapshot = _settings.value
        viewModelScope.launch { repository.save(snapshot) }
    }
}

data class ProfileState(val profiles: List<Profile>, val active: String) {
    fun find(name: String): Profile? = profiles.firstOrNull { it.name == name }
    val activeProfile: Profile? get() = find(active)
}
