package com.xreal.stereoview.ui

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
    FILL("Fill"),       // keep aspect, crop overflow
    FIT("Fit"),         // keep aspect, letterbox
    STRETCH("Stretch"), // ignore aspect (useful for half-SBS output)
}

/** Where the SBS frame is rendered. */
enum class OutputMode(val label: String) {
    AUTO("Glasses display if detected"),
    MAIN_SCREEN("This screen (mirrored)"),
}

data class StereoSettings(
    /** Horizontal image shift per eye in render pixels, -50..50. */
    val convergencePx: Float = 0f,
    /** Magnification about each eye's center, 1..2. Scales disparity along with the image. */
    val depth: Float = 1f,
    val swapEyes: Boolean = false,
    /** Exposure compensation in camera steps (see CONTROL_AE_COMPENSATION_RANGE). */
    val exposure: Int = 0,
    val scaleMode: ScaleMode = ScaleMode.FILL,
    val showHud: Boolean = false,
    /** Image rotation in degrees, or -1 to derive it from sensor and display orientation. */
    val rotationOverride: Int = -1,
    /** How long to wait for the partner eye's frame before drawing anyway. */
    val syncThresholdMs: Int = 33,
    /** Maximum stream height; the largest shared size up to this is used. */
    val maxResolution: Int = 1080,
    val outputMode: OutputMode = OutputMode.AUTO,
    /** [com.xreal.stereoview.camera.StereoRig.key] of a manually chosen camera pair, or empty for automatic. */
    val cameraPair: String = "",
) {
    companion object {
        const val CONVERGENCE_RANGE = 50f
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
    private var loaded = false

    init {
        viewModelScope.launch {
            _settings.value = repository.settings.first()
            loaded = true
        }
    }

    fun update(transform: (StereoSettings) -> StereoSettings) {
        // Ignore edits until the stored values are in, so defaults never overwrite them.
        if (!loaded) return
        _settings.update(transform)
        val snapshot = _settings.value
        viewModelScope.launch { repository.save(snapshot) }
    }
}
