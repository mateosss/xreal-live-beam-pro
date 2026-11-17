package com.xreal.stereoview.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.R
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.record.RecordingFiles
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The saved recordings, newest first, each with: copy its path, zip/unzip (replacing the
 * other form), copy to Downloads (with .nomedia), share (zips) and delete. One file operation
 * runs at a time, in the background, with progress shown at the top.
 */
class RecordingsDialog(
    private val context: Context,
    /** The folder being recorded into, if any: it can't be touched until the recording ends. */
    private val activeDir: () -> File?,
    private val setStatus: (CharSequence) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var dialog: AlertDialog? = null
    private var list: LinearLayout? = null
    private var progressText: TextView? = null
    private var busy: String? = null

    fun show() {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val progress = TextView(context).apply {
            setPadding(dp(24), dp(4), dp(24), dp(4))
            setTextColor(ContextCompat.getColor(context, R.color.accent))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(progress)
            addView(list)
        }
        this.list = list
        progressText = progress
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Recordings")
            .setView(NestedScrollView(context).apply { addView(content) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Storage…") { _, _ -> showStorageInfo() }
            .setOnDismissListener {
                dialog = null
                this.list = null
            }
            .show()
        refresh()
    }

    private fun entries(): List<File> = Recording.root(context).listFiles()
        ?.filter { (it.isDirectory && !it.name.endsWith(".unzipping")) || it.name.endsWith(".zip") }
        ?.sortedByDescending { it.name }.orEmpty()

    fun refresh() {
        val list = list ?: return
        list.removeAllViews()
        val root = Recording.root(context)
        progressText?.text = busy ?: "%.1f GB free · %s".format(
            (if (root.exists()) root.usableSpace else context.getExternalFilesDir(null)?.usableSpace ?: 0L) / 1e9,
            root.path,
        )
        val items = entries()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No recordings yet."
                setPadding(dp(24), dp(8), dp(24), dp(8))
            })
        }
        thread(name = "RecordingSizes") {
            // Sizes walk thousands of files: fill them in afterwards.
            val sizes = items.associateWith { RecordingFiles.size(it) }
            main.post { if (this.list === list) sizeViews.forEach { (f, v) -> sizes[f]?.let { v.text = describe(f, it) } } }
        }
        sizeViews.clear()
        items.forEach { list.addView(row(it)) }
    }

    private val sizeViews = mutableMapOf<File, TextView>()

    private fun describe(f: File, size: Long?): String {
        val kind = if (f.isDirectory) "folder" else "zip"
        val source = if (f.isDirectory) {
            runCatching { JSONObject(File(f, "metadata.json").readText()).let { m ->
                listOfNotNull(m.optString("source").takeIf { it.isNotEmpty() }, m.optString("status").takeIf { it.isNotEmpty() }).joinToString(", ")
            } }.getOrNull()
        } else null
        val sizeText = size?.let { if (it > 1e9) "%.2f GB".format(it / 1e9) else "%.0f MB".format(it / 1e6) } ?: "…"
        return listOfNotNull(kind, sizeText, source).joinToString(" · ")
    }

    private fun row(f: File): LinearLayout {
        val active = activeDir()?.canonicalPath == f.canonicalPath
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(12), dp(2))
        }
        row.addView(TextView(context).apply {
            text = if (active) "${f.name}  •  recording" else f.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        row.addView(TextView(context).apply {
            text = describe(f, null)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(context, R.color.panel_text_secondary))
            sizeViews[f] = this
        })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun button(label: String, action: () -> Unit) = buttons.addView(
            MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isEnabled = !active
                setOnClickListener {
                    if (busy != null) setStatus("Wait for: $busy") else action()
                }
            },
        )
        button("Copy path") { copyPath(f) }
        if (f.isDirectory) button("Zip") { run("Zipping ${f.name}", f) { p -> RecordingFiles.zip(f, p).let { "Zipped to ${it.name}; folder removed" } } }
        else button("Unzip") { run("Unzipping ${f.name}", f) { p -> RecordingFiles.unzip(f, p).let { "Unzipped to ${it.name}; zip removed" } } }
        button("To Downloads") { toDownloads(f) }
        button("Share") { share(f) }
        button("Delete") {
            Dialogs.confirm(context, "Delete ${f.name}?", "Delete") {
                f.deleteRecursively()
                setStatus("Deleted ${f.name}")
                refresh()
            }
        }
        row.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        })
        return row
    }

    private fun copyPath(f: File) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Recording path", f.absolutePath))
        setStatus("Copied ${f.absolutePath}")
    }

    /** Public Download/XREAL Stereo/: visible in file managers and over USB, kept after uninstall. */
    private fun downloadsDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOADS_FOLDER)

    private fun toDownloads(f: File) {
        val dest = downloadsDir()
        run("Copying ${f.name} to Downloads", f) { p ->
            val copy = RecordingFiles.copy(f, dest, p)
            "Copied to ${copy.path}" + if (copy.isDirectory) " (with .nomedia)" else ""
        }
    }

    private fun share(f: File) {
        if (f.isDirectory) {
            Dialogs.confirm(context, "Only a zip can be shared. Zip ${f.name} (the folder is replaced by the zip) and share it?", "Zip and share") {
                run("Zipping ${f.name}", f, then = { zip -> zip?.let(::launchShare) }) { p -> RecordingFiles.zip(f, p).also { result = it }.let { "Zipped to ${it.name}" } }
            }
            return
        }
        launchShare(f)
    }

    private var result: File? = null

    private fun launchShare(zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData extends the read grant to the share sheet itself (its preview).
        send.clipData = ClipData.newRawUri(zip.name, uri)
        context.startActivity(Intent.createChooser(send, "Share ${zip.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Runs [work] in the background with progress; [then] gets [result] on the main thread. */
    private fun run(label: String, f: File, then: (File?) -> Unit = {}, work: ((Long, Long) -> Unit) -> String) {
        if (activeDir()?.canonicalPath == f.canonicalPath) return
        busy = label
        result = null
        refresh()
        setStatus("$label…")
        var lastUpdate = 0L
        thread(name = "RecordingFiles") {
            val message = try {
                work { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 200) {
                        lastUpdate = now
                        val text = "$label… %.0f%% (%.0f / %.0f MB)".format(100.0 * done / total.coerceAtLeast(1), done / 1e6, total / 1e6)
                        main.post {
                            progressText?.text = text
                            setStatus(text)
                        }
                    }
                }
            } catch (e: Exception) {
                "⚠ $label failed: ${e.message}"
            }
            main.post {
                busy = null
                setStatus(message)
                refresh()
                then(result)
            }
        }
    }

    private fun showStorageInfo() {
        Dialogs.info(
            context, "Where recordings are stored",
            "Recordings are written to this app's own storage (${Recording.root(context).path}): the fastest place, " +
                "with no permission prompts and no media indexing.\n\n" +
                "• Over USB (MTP): Android/data/${context.packageName}/files/recordings\n" +
                "• adb pull /sdcard/Android/data/${context.packageName}/files/recordings\n" +
                "• To Downloads: copies to Download/$DOWNLOADS_FOLDER/, which other apps and file managers see and which survives " +
                "uninstalling this app; folders get a .nomedia file so galleries ignore the images.\n" +
                "• Zip: one file instead of thousands, much faster to copy over MTP and the only form that can be shared. " +
                "Images and video are stored uncompressed in the zip (they already are compressed), so zipping takes about " +
                "as long as copying.\n\n" +
                "Everything here is deleted when the app is uninstalled.",
        )
    }

    companion object {
        const val DOWNLOADS_FOLDER = "XREAL Stereo"
    }
}
