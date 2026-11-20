package com.xreal.stereoview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.xreal.stereoview.camera.CameraUtils
import com.xreal.stereoview.camera.StereoRig
import com.xreal.stereoview.glasses.GlassesDevice
import com.xreal.stereoview.glasses.GlassesProtocol
import com.xreal.stereoview.ui.CameraSource
import com.xreal.stereoview.ui.Dialogs
import com.xreal.stereoview.ui.DraftSettings
import com.xreal.stereoview.ui.FilePicker
import com.xreal.stereoview.ui.Profile
import com.xreal.stereoview.ui.RecordingsActivity
import com.xreal.stereoview.ui.SettingsSheet
import com.xreal.stereoview.ui.SettingsViewModel
import com.xreal.stereoview.ui.StereoSettings
import com.xreal.stereoview.ui.withProfile
import com.xreal.stereoview.util.CrashReporter
import kotlinx.coroutines.launch

/**
 * The start screen: pick a device (Beam Pro / phone cameras, the glasses' cameras, …), then
 * one of its profiles to start streaming with ([MainActivity]). Profiles are created, edited,
 * renamed, duplicated and deleted here; each belongs to one device and shows only the
 * settings that device has.
 */
class HomeActivity : AppCompatActivity() {
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var page: LinearLayout
    /** The device whose profiles are shown, or null for the device list. */
    private var device: CameraSource? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@HomeActivity, R.color.home_background))
            addView(page)
        })
        device = savedInstanceState?.getString(KEY_DEVICE)?.let { name -> CameraSource.entries.firstOrNull { it.name == name } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (device != null) {
                    device = null
                    render()
                } else {
                    finish()
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.profiles.collect { render() } }
        }
        CrashReporter.showPendingReport(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val pad = if (portrait) 16 else 32
        page.setPadding(dp(pad), dp(20), dp(pad), dp(24))
        render()
    }

    private val portrait: Boolean
        get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        device?.let { outState.putString(KEY_DEVICE, it.name) }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, RECEIVER_EXPORTED)
        render()
    }

    override fun onPause() {
        unregisterReceiver(usbReceiver)
        super.onPause()
    }

    private fun render() {
        page.removeAllViews()
        val d = device
        if (d == null) renderDevices() else renderProfiles(d)
    }

    // region Devices

    private fun renderDevices() {
        page.addView(title("XREAL Stereo"))
        page.addView(caption("Choose the cameras to stream and record from"))
        // Cards side by side in landscape, stacked in portrait.
        val row = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(8))
        }
        for (source in CameraSource.entries) {
            val (status, ready) = status(source)
            val count = viewModel.profiles.value.profiles.count { it.settings.cameraSource == source }
            row.addView(card(weight = if (portrait) 0f else 1f) { card ->
                card.addView(TextView(this).apply {
                    text = source.homeTitle
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                })
                card.addView(secondary(source.homeDescription).apply { setPadding(0, dp(4), 0, dp(10)) })
                card.addView(TextView(this).apply {
                    text = (if (ready) "● " else "○ ") + status
                    setTextColor(ContextCompat.getColor(this@HomeActivity, if (ready) R.color.accent else R.color.panel_text_secondary))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                })
                card.addView(secondary("$count profile${if (count == 1) "" else "s"}").apply { setPadding(0, dp(4), 0, 0) })
            }.apply {
                if (portrait) (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
                setOnClickListener {
                    device = source
                    render()
                }
            })
        }
        page.addView(row)
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        tools.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Recordings"
            setOnClickListener { RecordingsActivity.open(this@HomeActivity, null) }
        })
        page.addView(tools)
    }

    /** One line on whether [source] can stream now, and whether it can. */
    private fun status(source: CameraSource): Pair<String, Boolean> = when (source) {
        CameraSource.BEAM_PRO -> {
            val rigs = runCatching {
                CameraUtils.stereoRigs(getSystemService(CameraManager::class.java), display?.rotation ?: 0, 1080)
            }.getOrDefault(emptyList())
            if (rigs.isNotEmpty()) "${rigs.size} stereo camera pair${if (rigs.size == 1) "" else "s"}" to true
            else "No stereo pair: one camera feeds both eyes" to true
        }
        CameraSource.GLASSES -> {
            val usb = getSystemService(UsbManager::class.java).deviceList.values
            when {
                usb.any { it.vendorId == GlassesProtocol.VENDOR_ID && it.productId == GlassesProtocol.AIR_2_ULTRA } ->
                    "Air 2 Ultra connected" to true
                usb.any { it.vendorId == GlassesProtocol.VENDOR_ID } -> "XREAL glasses connected, but not an Air 2 Ultra" to false
                else -> "Connect Air 2 Ultra glasses over USB" to false
            }
        }
        CameraSource.OAK -> {
            val reason = com.xreal.stereoview.oak.OakNative.unavailableReason
            val found = getSystemService(UsbManager::class.java).deviceList.values.any { it.vendorId == com.xreal.stereoview.oak.OakDevice.VENDOR_ID }
            when {
                reason != null -> reason to false
                found -> "OAK camera connected" to true
                else -> "Connect an OAK camera over USB (USB3 recommended)" to false
            }
        }
    }

    // endregion

    // region Profiles of one device

    private fun renderProfiles(source: CameraSource) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "‹ Devices"
            isAllCaps = false
            setOnClickListener {
                device = null
                render()
            }
        })
        header.addView(title(source.homeTitle).apply {
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "New profile"
            setOnClickListener { newProfile(source) }
        })
        page.addView(header)
        val (status, _) = status(source)
        page.addView(caption("$status · tap a profile to start").apply { setPadding(dp(16), 0, 0, dp(12)) })

        val state = viewModel.profiles.value
        val profiles = state.profiles.filter { it.settings.cameraSource == source }
        if (profiles.isEmpty()) {
            page.addView(secondary("No profiles for this device. Create one, or restore the built-in ones.").apply {
                setPadding(dp(16), dp(8), 0, dp(8))
            })
        }
        for (p in profiles) {
            page.addView(card(weight = 0f) { card ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(TextView(this).apply {
                    text = if (p.name == state.active) "${p.name}  ·  last used" else p.name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    if (p.name == state.active) setTypeface(typeface, Typeface.BOLD)
                })
                texts.addView(secondary(p.summary))
                // Portrait: name and summary on top, buttons below at the end.
                if (portrait) {
                    card.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    line.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                } else {
                    line.addView(texts)
                }
                line.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Edit"
                    setOnClickListener { editProfile(p) }
                })
                line.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setIconResource(R.drawable.ic_more_vert)
                    iconPadding = 0
                    minWidth = 0
                    contentDescription = "Profile actions"
                    setOnClickListener { menu(this, p) }
                })
                line.addView(MaterialButton(this).apply {
                    text = "Start"
                    setIconResource(R.drawable.ic_play)
                    setOnClickListener { start(p) }
                })
                card.addView(line)
            }.apply {
                setOnClickListener { start(p) }
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
        }
        page.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Restore built-in profiles"
            setOnClickListener {
                Dialogs.confirm(
                    this@HomeActivity,
                    "Reset the built-in profiles (" + Profile.defaults.joinToString { "“${it.name}”" } +
                        ") to their original settings? Your own profiles are kept.",
                    "Restore",
                ) { viewModel.restoreDefaultProfiles() }
            }
        })
    }

    private fun start(p: Profile) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROFILE, p.name))
    }

    /** Edits [p]'s stored settings in the settings sheet's profile mode; saving replaces them. */
    private fun editProfile(p: Profile, isNew: Boolean = false) {
        lateinit var sheet: SettingsSheet
        val draft = DraftSettings(viewModel.settings.value.withProfile(p.settings)) { sheet.refresh() }
        sheet = SettingsSheet(
            this, viewModel, editorHost, draft,
            SettingsSheet.Editor(p.name) { edited ->
                viewModel.putProfile(Profile(p.name, edited))
                toast(if (isNew) "Created ${p.name}" else "Saved ${p.name}")
            },
        )
        sheet.show()
    }

    private fun newProfile(source: CameraSource) {
        val names = viewModel.profiles.value.profiles.map { it.name }.toSet()
        val suggestion = generateSequence(1) { it + 1 }.map { "${source.shortLabel} profile $it" }.first { it !in names }
        Dialogs.askText(this, "New ${source.shortLabel} profile", suggestion, "Create") { name ->
            if (name in names) {
                toast("A profile named $name exists")
                return@askText
            }
            // Start from the device's first built-in profile (sensible values for it).
            val base = Profile.defaults.firstOrNull { it.settings.cameraSource == source }?.settings
                ?: StereoSettings(cameraSource = source)
            val profile = Profile(name, base)
            viewModel.putProfile(profile)
            editProfile(profile, isNew = true)
        }
    }

    private fun menu(anchor: View, p: Profile) {
        PopupMenu(this, anchor).apply {
            menu.add("Rename…").setOnMenuItemClickListener {
                Dialogs.askText(this@HomeActivity, "Rename profile", p.name, "Rename") { name ->
                    if (viewModel.profiles.value.find(name) != null) toast("A profile named $name exists") else viewModel.renameProfile(p.name, name)
                }
                true
            }
            menu.add("Duplicate").setOnMenuItemClickListener {
                val names = viewModel.profiles.value.profiles.map { it.name }.toSet()
                val copy = generateSequence(2) { it + 1 }.map { "${p.name} $it" }.first { it !in names }
                viewModel.duplicateProfile(p.name, copy)
                true
            }
            menu.add("Delete").setOnMenuItemClickListener {
                Dialogs.confirm(this@HomeActivity, "Delete profile “${p.name}”?", "Delete") { viewModel.deleteProfile(p.name) }
                true
            }
        }.show()
    }

    private val filePicker = FilePicker(this)

    /** The settings sheet's host in the profile editor: no cameras are open here. */
    private val editorHost = object : SettingsSheet.Host {
        override val rig: StereoRig? by lazy {
            runCatching {
                CameraUtils.selectRig(getSystemService(CameraManager::class.java), display?.rotation ?: 0, 1080)
            }.getOrNull()
        }
        override val glassesControls: GlassesDevice.CameraControls? = null
        override fun isRecording() = false
        override fun cameraPairOptions(): List<Pair<String, String>> = emptyList()
        override fun setStatus(text: CharSequence) = toast(text)
        override fun showProfiles() = Unit
        override fun probeUsb() = Unit
        override fun showRecordings() = RecordingsActivity.open(this@HomeActivity, null)
        override fun pickFile(onPicked: (android.net.Uri, String?) -> Unit) = filePicker.pick(onPicked)
    }

    // endregion

    // region Views

    private fun toast(text: CharSequence) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
    }

    private fun caption(text: String) = secondary(text).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }

    private fun secondary(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.panel_text_secondary))
    }

    private fun card(weight: Float, fill: (LinearLayout) -> Unit) = MaterialCardView(this).apply {
        layoutParams = if (weight > 0) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { marginEnd = dp(12) }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        radius = dp(16).toFloat()
        isClickable = true
        isFocusable = true
        setCardBackgroundColor(ContextCompat.getColor(this@HomeActivity, R.color.home_card))
        strokeWidth = 0
        addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            fill(this)
        })
    }

    // endregion

    private companion object {
        const val KEY_DEVICE = "device"
    }
}
