package com.xreal.stereoview.record

import com.xreal.stereoview.ui.StereoSettings
import java.io.File

/** A running recording, from either camera source, as the activity controls it. */
interface ActiveRecording {
    val dir: File

    /** CLOCK_BOOTTIME at the start, for the elapsed-time display. */
    val startBoottimeNs: Long

    /** Set when recording fails midway; the activity then stops it and shows the message. */
    var error: String?
    fun onSettingsChanged(s: StereoSettings)

    /** How far file writing is behind, for the HUD. */
    val backlog: String

    /** A failed file write, if any (e.g. storage full). */
    fun writeErrors(): String?

    /** Closes all files and completes metadata.json. Blocking; call off the main thread. */
    fun finish(): String
}
