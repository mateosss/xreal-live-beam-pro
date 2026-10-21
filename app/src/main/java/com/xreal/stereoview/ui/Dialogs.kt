package com.xreal.stereoview.ui

import android.content.Context
import android.text.InputType
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Small dialog helpers shared by the settings sheet and the profile manager. */
object Dialogs {
    fun choose(context: Context, title: String, labels: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                onChosen(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for a line of text; [onDone] gets the trimmed, non-empty result. */
    fun askText(context: Context, title: String, initial: String, action: String, onDone: (String) -> Unit) {
        val input = TextInputEditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        val layout = TextInputLayout(context).apply { addView(input) }
        val frame = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(action) { _, _ -> input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirm(context: Context, message: String, action: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
