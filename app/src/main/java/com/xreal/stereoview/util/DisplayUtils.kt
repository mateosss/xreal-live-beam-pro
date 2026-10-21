package com.xreal.stereoview.util

import android.hardware.display.DisplayManager
import android.view.Display

object DisplayUtils {
    /**
     * The external display the glasses show up as (USB-C DP Alt), if the system exposes it as a
     * presentation display. When it doesn't, the glasses mirror the main screen instead.
     */
    fun findGlassesDisplay(displayManager: DisplayManager): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }

    fun describe(display: Display): String {
        val mode = display.mode
        return "${display.name} ${mode.physicalWidth}×${mode.physicalHeight} @ %.0f Hz".format(mode.refreshRate)
    }
}
