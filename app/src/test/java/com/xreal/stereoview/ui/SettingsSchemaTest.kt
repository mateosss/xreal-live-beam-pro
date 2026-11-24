package com.xreal.stereoview.ui

import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xreal.stereoview.oak.OakDevice
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class SettingsSchemaTest {
    private val defaults = StereoSettings()

    /** A value of the same type as [v], but different. */
    private fun other(v: Any): Any = when (v) {
        is Float -> v + 1.25f
        is Int -> v + 3
        is Long -> v + 7
        is Boolean -> !v
        is String -> "$v,X"
        is Enum<*> -> v.declaringJavaClass.enumConstants!!.let { it[(v.ordinal + 1) % it.size] }
        is List<*> -> listOf(OakDevice.Exposure(false, 1111, 222, 1), OakDevice.Exposure(true, 20, 100, -4), OakDevice.Exposure(false, 33_000, 1600, 9))
        else -> error("no other value for ${v::class}")
    }

    private fun changed(base: StereoSettings, settings: List<Setting<*, *>>) =
        settings.fold(base) { s, f -> f.withValue(s, other(f.valueIn(s))) }

    private val fields = StereoSettings::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

    @Test
    fun everyFieldOfStereoSettingsIsPersisted() {
        val covered = SettingsSchema.all.flatMap { f ->
            val s = f.withValue(defaults, other(f.valueIn(defaults)))
            fields.filter { it.get(s) != it.get(defaults) }.map { it.name }
        }.toSet()
        val missing = fields.map { it.name }.toSet() - covered
        assertTrue("StereoSettings fields without a SettingsSchema entry: $missing", missing.isEmpty())
    }

    @Test
    fun namesAndKeysAreUnique() {
        assertEquals(SettingsSchema.all.size, SettingsSchema.all.map { it.name }.toSet().size)
        assertEquals(SettingsSchema.all.size, SettingsSchema.all.map { it.key.name }.toSet().size)
    }

    @Test
    fun storeRoundTripKeepsEverySetting() {
        val all = changed(defaults, SettingsSchema.all)
        val p = mutablePreferencesOf()
        SettingsSchema.write(p, all)
        assertEquals(all, SettingsSchema.read(p))
    }

    /** DataStore keys of 0.16: renaming one would lose what users stored under it. */
    @Test
    fun storeKeysAreThoseOfEarlierVersions() {
        val keys = (
            "convergence_px depth swap_eyes exposure scale_mode portrait_scale_mode preview_style oak_rgb_fps show_hud " +
                "rotation_override sync_threshold_ms max_resolution output_mode camera_pair glasses_fov_deg sbs_layout stabilization " +
                "use_factory_calibration align_pitch_deg align_roll_deg glasses_align_pitch_deg glasses_align_roll_deg " +
                "oak_align_pitch_deg oak_align_roll_deg oak_mono_height oak_fps oak_rgb_height oak_jpeg_quality oak_imu_rate_hz " +
                "oak_usb3 oak_rgb_format oak_rgb_video_bitrate_mbps oak_exposures glasses_image_format oak_image_format " +
                "gray_jpeg_quality video_bitrate_mode video_keyframe_interval_s video_profile video_software_encoder " +
                "buffer_glasses_camera_s buffer_glasses_imu_s buffer_oak_camera_s buffer_oak_link_s buffer_oak_imu_s " +
                "buffer_phone_camera_s glasses_video_bitrate_mbps oak_video_bitrate_mbps target_fps manual_exposure " +
                "exposure_time_ns iso focus_mode record_view record_format record_bitrate_mbps imu_rate imu_streams camera_source " +
                "glasses_imu glasses_rectify glasses_manual_exposure glasses_exposure_100us glasses_gain"
            ).split(" ").toSet()
        assertEquals(keys, SettingsSchema.all.map { it.key.name }.toSet())
    }

    /** stereo_settings_v0.16.1.preferences_pb: the settings file of a 0.16.1 install (a test emulator). */
    @Test
    fun settingsStoredBy0161ReadBackIntact() {
        val stored = runBlocking { PreferencesSerializer.readFrom(javaClass.getResourceAsStream("/stereo_settings_v0.16.1.preferences_pb")!!.source().buffer()) }
        val s = SettingsSchema.read(stored)
        assertEquals(ScaleMode.FILL, s.scaleMode)
        assertEquals(FocusMode.INFINITY, s.focusMode)
        assertEquals(ImuRate.DIRECT_VERY_FAST, s.imuRate)
        // Every stored setting, written back, is the value that was stored (same key, type and value).
        val again = mutablePreferencesOf().also { SettingsSchema.write(it, s) }.asMap()
        val names = SettingsSchema.all.map { it.key.name }.toSet()
        val compared = stored.asMap().filterKeys { it.name in names }
        assertTrue(compared.size > 50)
        for ((k, v) in compared) assertEquals(k.name, v, again[k])
    }

    @Test
    fun emptyStoreGivesTheDefaults() {
        assertEquals(defaults, SettingsSchema.read(mutablePreferencesOf()))
    }

    @Test
    fun unreadableStoredValuesKeepTheirDefaults() {
        val p = mutablePreferencesOf(stringPreferencesKey("scale_mode") to "NO_SUCH_MODE", stringPreferencesKey("oak_exposures") to "not json")
        assertEquals(defaults, SettingsSchema.read(p))
    }

    /** Before 0.16 the OAK used the shared exposure settings for all of its cameras. */
    @Test
    fun storedSharedExposureBecomesThePerCameraOakExposure() {
        val p = mutablePreferencesOf(
            booleanPreferencesKey("manual_exposure") to true, longPreferencesKey("exposure_time_ns") to 2_000_000L,
            intPreferencesKey("iso") to 800,
        )
        assertEquals(List(3) { OakDevice.Exposure(false, 2000, 800, 0) }, SettingsSchema.read(p).oakExposures)
    }

    @Test
    fun profileJsonCarriesProfileSettingsOnly() {
        val all = changed(defaults, SettingsSchema.all)
        val json = SettingsSchema.toProfileJson(all)
        assertEquals(SettingsSchema.profileSettings.map { it.name }.toSet(), json.keys().asSequence().toSet())
        assertEquals(defaults.withProfile(all), SettingsSchema.fromProfileJson(json))
    }

    @Test
    fun withProfileTakesProfileSettingsAndKeepsTheDeviceSetup() {
        val device = changed(defaults, SettingsSchema.all.filter { !it.inProfile })
        val profile = changed(defaults, SettingsSchema.profileSettings)
        val applied = device.withProfile(profile)
        for (f in SettingsSchema.all) {
            assertEquals(f.name, f.valueIn(if (f.inProfile) profile else device), f.valueIn(applied))
        }
    }

    /** profiles_v0.16.json: a profile as 0.16 stored it, every setting away from its default. */
    @Test
    fun profilesStoredBy016ReadBackUnchanged() {
        val text = javaClass.classLoader!!.getResource("profiles_v0.16.json")!!.readText()
        val parsed = Profile.fromJson(text).single()
        assertEquals("Every setting", parsed.name)
        assertEquals(defaults.withProfile(SettingsFixtures.everyProfileSetting), parsed.settings)
        // And are written the same way.
        val again = JSONArray(Profile.toJson(listOf(parsed))).getJSONObject(0).getJSONObject("settings")
        val stored = JSONArray(text).getJSONObject(0).getJSONObject("settings")
        assertEquals(stored.keys().asSequence().toSet(), again.keys().asSequence().toSet())
        for (k in stored.keys()) assertEquals(k, stored.get(k).toString(), again.get(k).toString())
    }

    @Test
    fun unreadableProfileValuesKeepTheirDefaults() {
        val json = JSONObject().put("depth", "deep").put("targetFps", JSONObject()).put("scaleMode", 3).put("oakExposures", "x")
            .put("iso", "800")
        val s = SettingsSchema.fromProfileJson(json)
        // "800" reads as 800 (as org.json's optInt did); unreadable OAK exposures fall back to the shared ones.
        assertEquals(defaults.copy(iso = 800, oakExposures = StereoSettings.legacyOakExposures(false, defaults.exposureTimeNs, 800, 0)), s)
    }
}
