package com.xreal.stereoview.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.R

/**
 * One thing a list item says about itself: an icon with a few words in the list ([short]), the
 * whole sentence in its details ([long]). [on] false dims it (e.g. "no IMU").
 */
data class Fact(@param:DrawableRes val icon: Int, val short: String, val long: String, val on: Boolean = true)

/** The device's icon in lists. */
val CameraSource.icon: Int
    get() = when (this) {
        CameraSource.BEAM_PRO -> R.drawable.ic_smartphone
        CameraSource.GLASSES -> R.drawable.ic_glasses
        CameraSource.OAK -> R.drawable.ic_camera
    }

/** Views shared by the compact lists (devices, profiles, recordings). */
object ListItems {
    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** [facts] as icon + short text, wrapping onto more lines when they don't fit. */
    fun facts(context: Context, facts: List<Fact>): FlowRow = FlowRow(context, dp(context, 12), dp(context, 2)).apply {
        for (f in facts) addView(fact(context, f, small = true))
    }

    private fun fact(context: Context, f: Fact, small: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = if (f.on) 1f else 0.45f
        val color = ContextCompat.getColor(context, R.color.panel_text_secondary)
        val size = dp(context, if (small) 14 else 20)
        addView(ImageView(context).apply {
            setImageResource(f.icon)
            setColorFilter(color)
            contentDescription = null
        }, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, if (small) 4 else 16) })
        addView(TextView(context).apply {
            text = if (small) f.short else f.long
            setTextAppearance(if (small) com.google.android.material.R.style.TextAppearance_Material3_BodySmall else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(if (small) color else ContextCompat.getColor(context, R.color.panel_text))
            if (small) maxLines = 1
        }, LinearLayout.LayoutParams(if (small) ViewGroup.LayoutParams.WRAP_CONTENT else 0, ViewGroup.LayoutParams.WRAP_CONTENT, if (small) 0f else 1f))
        if (!small) setPadding(0, dp(context, 6), 0, dp(context, 6))
        if (small) TooltipCompat.setTooltipText(this, f.long)
    }

    /** A small rounded label, e.g. "built-in". */
    fun pill(context: Context, text: String, @ColorRes color: Int): TextView = TextView(context).apply {
        this.text = text
        val c = ContextCompat.getColor(context, color)
        setTextColor(c)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(context, 7), dp(context, 1), dp(context, 7), dp(context, 2))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setStroke(dp(context, 1), c)
        }
    }

    /** A borderless icon-only button, its [description] shown on long press. */
    fun iconButton(context: Context, @DrawableRes icon: Int, description: String, @ColorRes tint: Int? = null, onClick: (View) -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setIconResource(icon)
            tint?.let { iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, it)) }
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 10), paddingTop, dp(context, 10), paddingBottom)
            contentDescription = description
            TooltipCompat.setTooltipText(this, description)
            setOnClickListener(onClick)
        }

    /** A dialog listing every fact in full, then [extra] text, if any. */
    fun showDetails(context: Context, title: String, facts: List<Fact>, extra: String? = null) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
            for (f in facts) addView(fact(context, f, small = false))
            if (extra != null) {
                addView(TextView(context).apply {
                    text = extra
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
                    setTextIsSelectable(true)
                    setPadding(0, dp(context, 10), 0, 0)
                })
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(NestedScrollView(context).apply { addView(list) })
            .setPositiveButton("Close", null)
            .show()
    }
}

/** Lays children out left to right, wrapping onto a new line when the width runs out. */
class FlowRow(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private var hGap = 0
    private var vGap = 0

    constructor(context: Context, hGap: Int, vGap: Int) : this(context) {
        this.hGap = hGap
        this.vGap = vGap
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), if (unbounded) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (!unbounded && x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            x += child.measuredWidth + hGap
            widest = maxOf(widest, x - hGap)
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        val width = if (unbounded) widest else resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            // Vertically centered on the line would need a second pass; the items are one text line high.
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + hGap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
