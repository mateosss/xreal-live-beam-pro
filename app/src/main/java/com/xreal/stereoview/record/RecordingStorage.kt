package com.xreal.stereoview.record

import android.content.Context
import android.os.SystemClock
import com.xreal.stereoview.ui.StereoSettings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where recordings go and how a new one's directory is named. */
object RecordingStorage {
    /** App-specific external storage, reachable over USB (MTP) and adb. */
    fun root(context: Context): File = File(context.getExternalFilesDir(null), "recordings")

    /** Creates the directory of a recording starting now (see [createDir]). */
    fun createDir(context: Context): File = createDir(root(context), Date())

    /**
     * Creates `<root>/yyyy-MM-dd_HH-mm-ss` for [now]; if that exists (two recordings started
     * within one second), `…_2`, `…_3` and so on. Throws if none can be created.
     */
    fun createDir(root: File, now: Date): File {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
        root.mkdirs()
        for (n in 1..100) {
            val dir = File(root, if (n == 1) name else "${name}_$n")
            if (dir.mkdir()) return dir
        }
        throw IllegalStateException("Cannot create a recording directory in $root")
    }
}

/** settings.jsonl: the settings at the start of a recording and after every change, with their time. */
class SettingsLog(dir: File) {
    private val out = LineWriter(File(dir, "settings.jsonl"))
    val error: Throwable? get() = out.error

    fun log(s: StereoSettings) = out.line(JSONObject().put("boottime_ns", SystemClock.elapsedRealtimeNanos()).put("settings", Metadata.settings(s)).toString())

    fun close() = out.close()
}
