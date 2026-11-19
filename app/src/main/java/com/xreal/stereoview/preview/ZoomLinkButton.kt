package com.xreal.stereoview.preview

import android.content.Context
import com.google.android.material.button.MaterialButton
import com.xreal.stereoview.R

/**
 * A one-icon toggle: a chain while the cameras zoom together, a broken chain while each zooms
 * on its own. [onToggle] gets the new state; the icon follows it.
 */
class ZoomLinkButton(context: Context, linked: Boolean, private val onToggle: (Boolean) -> Unit) :
    MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) {
    var linked = linked
        set(value) {
            field = value
            show()
        }

    init {
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()
        text = null
        iconPadding = 0
        iconGravity = ICON_GRAVITY_TEXT_START
        iconSize = (20 * density).toInt()
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
        setOnClickListener {
            this@ZoomLinkButton.linked = !this@ZoomLinkButton.linked
            onToggle(this@ZoomLinkButton.linked)
        }
        show()
    }

    private fun show() {
        setIconResource(if (linked) R.drawable.ic_link else R.drawable.ic_link_off)
        val description = if (linked) "Zoom linked: all cameras show the same region. Tap to zoom each camera on its own"
        else "Free zoom: each camera zooms on its own. Tap to link all cameras to cam0's zoom"
        contentDescription = description
        tooltipText = description
    }
}
