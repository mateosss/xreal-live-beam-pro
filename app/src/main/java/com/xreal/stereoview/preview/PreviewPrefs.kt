package com.xreal.stereoview.preview

import android.content.Context

/** Display preferences of the camera previews (recording preview and live capture view). */
object PreviewPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("preview", Context.MODE_PRIVATE)

    /** Whether zooming one camera tile zooms all of them (same image region everywhere). */
    fun linkZoom(context: Context): Boolean = prefs(context).getBoolean("link_zoom", true)

    fun setLinkZoom(context: Context, on: Boolean) = prefs(context).edit().putBoolean("link_zoom", on).apply()
}
