package com.xreal.stereoview.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView as tall as its content up to [maxHeightPx] (0 = no limit), scrolling beyond. */
class MaxHeightScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    var maxHeightPx = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}
