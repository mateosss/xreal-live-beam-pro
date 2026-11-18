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
/** Label of [ScaleMode.REAL] in the portrait phone preview, where it shows the raw images whole. */
const val PORTRAIT_RAW_LABEL = "Raw"

/** Labels and order of the portrait preview's modes. */
val PORTRAIT_SCALE_MODES = listOf(ScaleMode.REAL, ScaleMode.FIT, ScaleMode.FILL, ScaleMode.STRETCH)
fun portraitScaleLabel(m: ScaleMode) = if (m == ScaleMode.REAL) PORTRAIT_RAW_LABEL else m.label

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
enum class CameraSource(val label: String, val shortLabel: String, val homeTitle: String, val homeDescription: String) {
    BEAM_PRO(
        "Beam Pro / phone rear cameras", "Beam Pro", "Beam Pro / Phone",
        "The device's rear stereo cameras (color, up to 1080p) with its IMU",
    ),
    GLASSES(
        "Glasses tracking cameras (Air 2 Ultra, over USB)", "Glasses", "Glasses",
        "The Air 2 Ultra's two grayscale tracking cameras (480×640, 30 fps) and its IMU, over USB",
    ),
    OAK(
        "Luxonis OAK camera (DepthAI, over USB)", "OAK", "DepthAI camera",
        "A Luxonis OAK (e.g. OAK-D Pro W): global-shutter grayscale stereo pair, color camera and IMU, over USB",
    ),
}

/** Which image a recording stores per camera. */
enum class RecordView(val label: String) {
    RAW("Raw: camera images as streamed (distorted, unrotated)"),
    RECTIFIED("Rectified: what each eye sees (undistorted, aligned)"),
}

/** How recorded frames are encoded. */
enum class RecordFormat(val label: String, val mime: String?, val videoFile: String? = null) {
    H264("H.264 video in MKV (crash-safe, recommended for long recordings)", "video/avc", "data.mkv"),
    HEVC("H.265 video in MP4 (smaller, but lost if the recording doesn't end normally)", "video/hevc", "data.mp4"),
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
    /**
     * Scale of the phone's own preview in portrait, where each eye gets half the screen. Its 1:1
     * slot means Raw there ([PORTRAIT_RAW_LABEL]): the unrectified images, whole and undistorted.
     * A display preference, not in profiles.
     */
    val portraitScaleMode: ScaleMode = ScaleMode.REAL,
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
    /** Beam Pro cameras: vertical alignment correction between the eyes, degrees. */
    val beamProAlignPitchDeg: Float = 0f,
    /** Beam Pro cameras: rotational alignment correction between the eyes, degrees. */
    val beamProAlignRollDeg: Float = 0f,
    /** Glasses cameras: the same corrections, kept separately (different cameras). */
    val glassesAlignPitchDeg: Float = 0f,
    val glassesAlignRollDeg: Float = 0f,
    val oakAlignPitchDeg: Float = 0f,
    val oakAlignRollDeg: Float = 0f,
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
    /** Glasses cameras: how recorded images are stored. */
    val glassesImageFormat: com.xreal.stereoview.record.GrayImageFormat = com.xreal.stereoview.record.GrayImageFormat.PNG,
    /** OAK mono cameras: how recorded images are stored. */
    val oakImageFormat: com.xreal.stereoview.record.GrayImageFormat = com.xreal.stereoview.record.GrayImageFormat.PNG,
    /** JPEG quality of recorded grayscale images (glasses, OAK mono). */
    val grayJpegQuality: Int = 90,
    /** Video recordings: rate control, keyframe spacing and H.264 profile (all devices). */
    val videoBitrateMode: com.xreal.stereoview.record.BitrateMode = com.xreal.stereoview.record.BitrateMode.CBR,
    val videoKeyframeIntervalS: Float = 1f,
    val videoProfile: com.xreal.stereoview.record.H264Profile = com.xreal.stereoview.record.H264Profile.HIGH,
    /** Bitrate per camera of H.264 recordings of the glasses and OAK mono cameras, Mbit/s. */
    val glassesVideoBitrateMbps: Int = 4,
    val oakVideoBitrateMbps: Int = 12,
    /** OAK: mono camera height (400, 720 or 800 rows; 1280 wide except 400p, 640). */
    val oakMonoHeight: Int = 800,
    /** OAK: frame rate of every camera. */
    val oakFps: Int = 30,
    /** OAK: color camera height (800 or 720), or 0 to leave it off. */
    val oakRgbHeight: Int = 800,
    /** OAK: JPEG quality of the color images (encoded on the camera). */
    val oakJpegQuality: Int = 95,
    /** OAK: IMU rate in Hz, or 0 to leave it off. */
    val oakImuRateHz: Int = 200,
    /** OAK: allow USB3 (off forces USB2, for flaky cables or hubs). */
    val oakUsb3: Boolean = true,
) {
    /** H.264/H.265 options for a stream at [bitrateMbps]. */
    fun encoderOptions(bitrateMbps: Int) =
        com.xreal.stereoview.record.EncoderOptions(bitrateMbps * 1_000_000, videoBitrateMode, videoKeyframeIntervalS, videoProfile)

    /** Eye alignment of the current camera source. */
    val alignPitchDeg: Float get() = when (cameraSource) {
        CameraSource.GLASSES -> glassesAlignPitchDeg
        CameraSource.OAK -> oakAlignPitchDeg
        CameraSource.BEAM_PRO -> beamProAlignPitchDeg
    }
    val alignRollDeg: Float get() = when (cameraSource) {
        CameraSource.GLASSES -> glassesAlignRollDeg
        CameraSource.OAK -> oakAlignRollDeg
        CameraSource.BEAM_PRO -> beamProAlignRollDeg
    }

    /** These settings with the current camera source's eye alignment replaced. */
    fun withAlignment(pitchDeg: Float, rollDeg: Float) = when (cameraSource) {
        CameraSource.GLASSES -> copy(glassesAlignPitchDeg = pitchDeg, glassesAlignRollDeg = rollDeg)
        CameraSource.OAK -> copy(oakAlignPitchDeg = pitchDeg, oakAlignRollDeg = rollDeg)
        CameraSource.BEAM_PRO -> copy(beamProAlignPitchDeg = pitchDeg, beamProAlignRollDeg = rollDeg)
    }

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
        val PORTRAIT_SCALE_MODE = stringPreferencesKey("portrait_scale_mode")
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
        val GLASSES_ALIGN_PITCH = floatPreferencesKey("glasses_align_pitch_deg")
        val GLASSES_ALIGN_ROLL = floatPreferencesKey("glasses_align_roll_deg")
        val OAK_ALIGN_PITCH = floatPreferencesKey("oak_align_pitch_deg")
        val OAK_ALIGN_ROLL = floatPreferencesKey("oak_align_roll_deg")
        val OAK_MONO_HEIGHT = intPreferencesKey("oak_mono_height")
        val OAK_FPS = intPreferencesKey("oak_fps")
        val OAK_RGB_HEIGHT = intPreferencesKey("oak_rgb_height")
        val OAK_JPEG_QUALITY = intPreferencesKey("oak_jpeg_quality")
        val OAK_IMU_RATE = intPreferencesKey("oak_imu_rate_hz")
        val OAK_USB3 = booleanPreferencesKey("oak_usb3")
        val GLASSES_IMAGE_FORMAT = stringPreferencesKey("glasses_image_format")
        val OAK_IMAGE_FORMAT = stringPreferencesKey("oak_image_format")
        val GRAY_JPEG_QUALITY = intPreferencesKey("gray_jpeg_quality")
        val VIDEO_BITRATE_MODE = stringPreferencesKey("video_bitrate_mode")
        val VIDEO_KEYFRAME_INTERVAL = floatPreferencesKey("video_keyframe_interval_s")
        val VIDEO_PROFILE = stringPreferencesKey("video_profile")
        val GLASSES_VIDEO_BITRATE = intPreferencesKey("glasses_video_bitrate_mbps")
        val OAK_VIDEO_BITRATE = intPreferencesKey("oak_video_bitrate_mbps")
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
        val stored = p[Keys.PROFILES]?.let { runCatching { Profile.migrate(Profile.fromJson(it)) }.getOrNull() }
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
            portraitScaleMode = enumOrDefault(p[Keys.PORTRAIT_SCALE_MODE], defaults.portraitScaleMode),
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
            beamProAlignPitchDeg = p[Keys.ALIGN_PITCH] ?: defaults.beamProAlignPitchDeg,
            beamProAlignRollDeg = p[Keys.ALIGN_ROLL] ?: defaults.beamProAlignRollDeg,
            glassesAlignPitchDeg = p[Keys.GLASSES_ALIGN_PITCH] ?: defaults.glassesAlignPitchDeg,
            glassesAlignRollDeg = p[Keys.GLASSES_ALIGN_ROLL] ?: defaults.glassesAlignRollDeg,
            oakAlignPitchDeg = p[Keys.OAK_ALIGN_PITCH] ?: defaults.oakAlignPitchDeg,
            oakAlignRollDeg = p[Keys.OAK_ALIGN_ROLL] ?: defaults.oakAlignRollDeg,
            oakMonoHeight = p[Keys.OAK_MONO_HEIGHT] ?: defaults.oakMonoHeight,
            oakFps = p[Keys.OAK_FPS] ?: defaults.oakFps,
            oakRgbHeight = p[Keys.OAK_RGB_HEIGHT] ?: defaults.oakRgbHeight,
            oakJpegQuality = p[Keys.OAK_JPEG_QUALITY] ?: defaults.oakJpegQuality,
            oakImuRateHz = p[Keys.OAK_IMU_RATE] ?: defaults.oakImuRateHz,
            oakUsb3 = p[Keys.OAK_USB3] ?: defaults.oakUsb3,
            glassesImageFormat = enumOrDefault(p[Keys.GLASSES_IMAGE_FORMAT], defaults.glassesImageFormat),
            oakImageFormat = enumOrDefault(p[Keys.OAK_IMAGE_FORMAT], defaults.oakImageFormat),
            grayJpegQuality = p[Keys.GRAY_JPEG_QUALITY] ?: defaults.grayJpegQuality,
            videoBitrateMode = enumOrDefault(p[Keys.VIDEO_BITRATE_MODE], defaults.videoBitrateMode),
            videoKeyframeIntervalS = p[Keys.VIDEO_KEYFRAME_INTERVAL] ?: defaults.videoKeyframeIntervalS,
            videoProfile = enumOrDefault(p[Keys.VIDEO_PROFILE], defaults.videoProfile),
            glassesVideoBitrateMbps = p[Keys.GLASSES_VIDEO_BITRATE] ?: defaults.glassesVideoBitrateMbps,
            oakVideoBitrateMbps = p[Keys.OAK_VIDEO_BITRATE] ?: defaults.oakVideoBitrateMbps,
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
            p[Keys.PORTRAIT_SCALE_MODE] = s.portraitScaleMode.name
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
            p[Keys.ALIGN_PITCH] = s.beamProAlignPitchDeg
            p[Keys.ALIGN_ROLL] = s.beamProAlignRollDeg
            p[Keys.GLASSES_ALIGN_PITCH] = s.glassesAlignPitchDeg
            p[Keys.GLASSES_ALIGN_ROLL] = s.glassesAlignRollDeg
            p[Keys.OAK_ALIGN_PITCH] = s.oakAlignPitchDeg
            p[Keys.OAK_ALIGN_ROLL] = s.oakAlignRollDeg
            p[Keys.OAK_MONO_HEIGHT] = s.oakMonoHeight
            p[Keys.OAK_FPS] = s.oakFps
            p[Keys.OAK_RGB_HEIGHT] = s.oakRgbHeight
            p[Keys.OAK_JPEG_QUALITY] = s.oakJpegQuality
            p[Keys.OAK_IMU_RATE] = s.oakImuRateHz
            p[Keys.OAK_USB3] = s.oakUsb3
            p[Keys.GLASSES_IMAGE_FORMAT] = s.glassesImageFormat.name
            p[Keys.OAK_IMAGE_FORMAT] = s.oakImageFormat.name
            p[Keys.GRAY_JPEG_QUALITY] = s.grayJpegQuality
            p[Keys.VIDEO_BITRATE_MODE] = s.videoBitrateMode.name
            p[Keys.VIDEO_KEYFRAME_INTERVAL] = s.videoKeyframeIntervalS
            p[Keys.VIDEO_PROFILE] = s.videoProfile.name
            p[Keys.GLASSES_VIDEO_BITRATE] = s.glassesVideoBitrateMbps
            p[Keys.OAK_VIDEO_BITRATE] = s.oakVideoBitrateMbps
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

    private val _loadedFlow = MutableStateFlow(false)
    /**
     * Becomes true once the stored settings are in. [settings] alone can't signal it: when the
     * stored values equal the defaults it doesn't emit again.
     */
    val loadedFlow: StateFlow<Boolean> = _loadedFlow.asStateFlow()

    /** Profile to switch to once the stored settings are in (set before they load). */
    private var startProfile: String? = null

    init {
        viewModelScope.launch {
            val profiles = repository.profiles.first()
            _profiles.value = profiles
            val stored = repository.settings.first()
            loaded = true
            val start = startProfile?.let(profiles::find)
            if (start != null) {
                _settings.value = stored.withProfile(start.settings)
                setProfiles(profiles.copy(active = start.name))
                repository.save(_settings.value)
            } else {
                _settings.value = stored
            }
            _loadedFlow.value = true
            // Follow profile edits made elsewhere (another screen saves them to the same store).
            repository.profiles.collect { _profiles.value = it }
        }
    }

    /** Switches to profile [name] now, or as soon as the stored settings are loaded. */
    fun startWithProfile(name: String) {
        if (loaded) selectProfile(name) else startProfile = name
    }

    /** Stores [profile] (new, or replacing the one with its name) without applying it. */
    fun putProfile(profile: Profile) {
        val state = _profiles.value
        val list = if (state.find(profile.name) != null) state.profiles.map { if (it.name == profile.name) profile else it } else state.profiles + profile
        setProfiles(state.copy(profiles = list))
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
