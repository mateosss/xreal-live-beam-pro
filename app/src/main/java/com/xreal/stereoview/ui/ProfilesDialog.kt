package com.xreal.stereoview.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.R

/**
 * Lists the profiles: tap one to switch to it; its menu updates it from the current settings,
 * renames, duplicates or deletes it. Editing a profile means switching to it, changing
 * settings (the panel shows "•" while they differ) and saving.
 */
class ProfilesDialog(
    private val context: Context,
    private val viewModel: SettingsViewModel,
    private val isRecording: () -> Boolean,
    private val setStatus: (CharSequence) -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var list: LinearLayout? = null
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        this.list = list
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Profiles")
            .setView(NestedScrollView(context).apply { addView(list) })
            .setPositiveButton("New from current…") { _, _ -> newProfile() }
            .setNeutralButton("Restore built-ins") { _, _ ->
                Dialogs.confirm(
                    context,
                    "Reset “${Profile.PASSTHROUGH}” and “${Profile.EUROC}” to their original settings? Your own profiles are kept.",
                    "Restore",
                ) { viewModel.restoreDefaultProfiles() }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener {
                dialog = null
                this.list = null
            }
            .show()
        refresh()
    }

    /** Rebuilds the list, if the dialog is open. */
    fun refresh() {
        val list = list ?: return
        list.removeAllViews()
        val state = viewModel.profiles.value
        val current = viewModel.settings.value
        for (profile in state.profiles) {
            val active = profile.name == state.active
            val edited = active && profile.differsFrom(current)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(8), dp(8))
                val ripple = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { select(profile, state, current) }
            }
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(ContextCompat.getColor(context, R.color.accent))
                alpha = if (active) 1f else 0f
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
            })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = if (edited) "${profile.name}  •  edited" else profile.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                if (active) setTypeface(typeface, Typeface.BOLD)
            })
            texts.addView(TextView(context).apply {
                text = profile.summary
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
            })
            row.addView(texts)
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setIconResource(R.drawable.ic_more_vert)
                iconPadding = 0
                minWidth = 0
                contentDescription = "Profile actions"
                setOnClickListener { menu(this, profile, active) }
            })
            list.addView(row)
        }
        if (state.profiles.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No profiles. Save the current settings as one, or restore the built-in ones."
                setPadding(dp(24), dp(8), dp(24), dp(8))
            })
        }
    }

    private fun select(profile: Profile, state: ProfileState, current: StereoSettings) {
        if (profile.name == state.active && !profile.differsFrom(current)) return
        if (isRecording()) {
            setStatus("Stop the recording to switch profiles")
            return
        }
        val activeEdited = state.activeProfile?.takeIf { it.differsFrom(current) }
        val apply = {
            viewModel.selectProfile(profile.name)
            setStatus("Profile: ${profile.name}")
        }
        if (activeEdited != null) {
            val verb = if (profile.name == state.active) "Revert to" else "Switch to"
            Dialogs.confirm(context, "Discard your unsaved changes to “${activeEdited.name}”?", "$verb ${profile.name}") { apply() }
        } else {
            apply()
        }
    }

    private fun menu(anchor: MaterialButton, profile: Profile, active: Boolean) {
        PopupMenu(context, anchor).apply {
            menu.add(if (active) "Save current settings" else "Overwrite with current settings").setOnMenuItemClickListener {
                viewModel.saveProfile(profile.name)
                setStatus("Saved profile ${profile.name}")
                true
            }
            menu.add("Rename…").setOnMenuItemClickListener {
                Dialogs.askText(context, "Rename profile", profile.name, "Rename") { name ->
                    if (viewModel.profiles.value.find(name) != null) setStatus("A profile named $name exists") else viewModel.renameProfile(profile.name, name)
                }
                true
            }
            menu.add("Duplicate").setOnMenuItemClickListener {
                val names = viewModel.profiles.value.profiles.map { it.name }.toSet()
                val copy = generateSequence(2) { it + 1 }.map { "${profile.name} $it" }.first { it !in names }
                viewModel.duplicateProfile(profile.name, copy)
                setStatus("Created $copy")
                true
            }
            menu.add("Delete").setOnMenuItemClickListener {
                Dialogs.confirm(context, "Delete profile “${profile.name}”? The current settings stay as they are.", "Delete") {
                    viewModel.deleteProfile(profile.name)
                }
                true
            }
        }.show()
    }

    private fun newProfile() {
        val names = viewModel.profiles.value.profiles.map { it.name }.toSet()
        val suggestion = generateSequence(1) { it + 1 }.map { "My profile $it" }.first { it !in names }
        Dialogs.askText(context, "Save current settings as", suggestion, "Save") { name ->
            if (name in names) {
                Dialogs.confirm(context, "Replace profile “$name”?", "Replace") { viewModel.saveProfile(name) }
            } else {
                viewModel.saveProfile(name)
                setStatus("Saved profile $name")
            }
        }
    }
}
