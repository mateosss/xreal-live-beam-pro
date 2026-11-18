package com.xreal.stereoview.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.xreal.stereoview.R
import com.xreal.stereoview.preview.RecordingPreviewActivity
import com.xreal.stereoview.record.Recording
import com.xreal.stereoview.record.RecordingFiles
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The saved recordings, newest first. Each card shows its kind, size, source, duration and
 * status, with Preview (the recording viewer) and actions: copy its path, zip/unzip (replacing
 * the other form), copy to Downloads (with .nomedia), share (zips) and delete. One file
 * operation runs at a time in the background, with progress at the top.
 */
class RecordingsActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var busy: String? = null
    /** The folder being recorded into, if any: it can't be touched until the recording ends. */
    private val activeDir: File? by lazy { intent.getStringExtra(EXTRA_ACTIVE)?.let(::File) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.home_background))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(12), dp(4))
        }
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            minWidth = 0
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "Recordings"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Storage…"
            isAllCaps = false
            setOnClickListener { showStorageInfo() }
        })
        root.addView(bar)
        status = TextView(this).apply {
            setPadding(dp(20), 0, dp(20), dp(8))
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.accent))
        }
        root.addView(status)
        topProgress = LinearProgressIndicator(this).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(topProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(20); marginEnd = dp(20); bottomMargin = dp(8)
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    private fun entries(): List<File> = Recording.root(this).listFiles()
        ?.filter { (it.isDirectory && !it.name.endsWith(".unzipping") && !it.name.endsWith(".part")) || it.name.endsWith(".zip") }
        ?.sortedByDescending { it.name }.orEmpty()

    private fun refresh() {
        list.removeAllViews()
        cardProgress = null
        topProgress.visibility = if (busy != null) View.VISIBLE else View.GONE
        val root = Recording.root(this)
        status.text = busy ?: "%.1f GB free · %s".format(
            (if (root.exists()) root.usableSpace else getExternalFilesDir(null)?.usableSpace ?: 0L) / 1e9, root.path,
        )
        val items = entries()
        if (items.isEmpty()) list.addView(secondary("No recordings yet. Start a device and press Rec."))
        val details = HashMap<File, TextView>()
        items.forEach { list.addView(card(it, details)) }
        thread(name = "RecordingSizes") {
            // Sizes walk thousands of files: fill them in afterwards.
            val sizes = items.associateWith { RecordingFiles.size(it) }
            main.post { if (!isDestroyed) details.forEach { (f, v) -> v.text = describe(f, sizes[f]) } }
        }
    }

    private fun describe(f: File, size: Long?): String {
        val meta = if (f.isDirectory) runCatching { JSONObject(File(f, "metadata.json").readText()) }.getOrNull() else null
        val sizeText = size?.let { if (it > 1e9) "%.2f GB".format(it / 1e9) else "%.0f MB".format(it / 1e6) } ?: "…"
        return listOfNotNull(
            if (f.isDirectory) "folder" else "zip",
            sizeText,
            meta?.optString("source")?.ifEmpty { null }?.replace('_', ' '),
            meta?.optDouble("duration_s")?.takeIf { !it.isNaN() }?.let { "%.1f s".format(it) },
            meta?.optJSONObject("trimmed_from")?.let { "trimmed" },
            meta?.optString("status")?.ifEmpty { null }?.takeIf { it != "complete" },
        ).joinToString(" · ")
    }

    private fun card(f: File, details: MutableMap<File, TextView>): View {
        val active = activeDir?.canonicalPath == f.canonicalPath
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(6))
        }
        content.addView(TextView(this).apply {
            text = if (active) "${f.name}  •  recording" else f.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(secondary(describe(f, null)).also { details[f] = it })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        fun button(label: String, filled: Boolean = false, action: () -> Unit) = buttons.addView(MaterialButton(
            this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.borderlessButtonStyle,
        ).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            isEnabled = !active
            setOnClickListener { if (busy != null) toast("Wait for: $busy") else action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        button("Preview", filled = true) { preview(f) }
        button("Rename") { rename(f) }
        if (f.isDirectory) button("Zip") { zip(f) } else button("Unzip") { unzip(f) }
        button("To Downloads") { toDownloads(f) }
        // ⋮ stays pinned at the right; the other buttons scroll if they don't fit.
        val more = (MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setIconResource(R.drawable.ic_more_vert)
            iconPadding = 0
            minWidth = 0
            contentDescription = "More"
            isEnabled = !active
            setOnClickListener { v ->
                PopupMenu(this@RecordingsActivity, v).apply {
                    fun item(label: String, action: () -> Unit) = menu.add(label).setOnMenuItemClickListener {
                        if (busy != null) toast("Wait for: $busy") else action()
                        true
                    }
                    item("Copy path") { copyPath(f) }
                    item("Share") { share(f) }
                    menu.add("Delete").setOnMenuItemClickListener {
                        Dialogs.confirm(this@RecordingsActivity, "Delete ${f.name}?", "Delete") {
                            f.deleteRecursively()
                            toast("Deleted ${f.name}")
                            refresh()
                        }
                        true
                    }
                }.show()
            }
        })
        if (busyFile?.canonicalPath == f.canonicalPath) {
            content.addView(LinearProgressIndicator(this).apply {
                isIndeterminate = busyFraction < 0
                if (busyFraction >= 0) setProgressCompat((busyFraction * 1000).toInt(), false)
                max = 1000
                cardProgress = this
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); marginEnd = dp(8) })
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(this@RecordingsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(buttons)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(more)
        })
        return MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.home_card))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(content)
            if (!active) setOnClickListener { preview(f) }
        }
    }

    // region Actions

    private fun preview(f: File) {
        if (f.isDirectory) {
            RecordingPreviewActivity.open(this, f)
            return
        }
        Dialogs.confirm(this, "${f.name} is zipped. Unzip it (the zip is replaced by the folder) to preview it?", "Unzip and preview") {
            run("Unzipping ${f.name}", f, then = { dir -> dir?.let { RecordingPreviewActivity.open(this, it) } }) { p ->
                RecordingFiles.unzip(f, p).also { result = it }.let { "Unzipped to ${it.name}" }
            }
        }
    }

    private fun zip(f: File) = run("Zipping ${f.name}", f) { p -> RecordingFiles.zip(f, p).let { "Zipped to ${it.name}; folder removed" } }

    private fun unzip(f: File) = run("Unzipping ${f.name}", f) { p -> RecordingFiles.unzip(f, p).let { "Unzipped to ${it.name}; zip removed" } }

    private fun copyPath(f: File) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Recording path", f.absolutePath))
        toast("Copied ${f.absolutePath}")
    }

    /** Public Download/XREAL Stereo/: visible in file managers and over USB, kept after uninstall. */
    private fun toDownloads(f: File) {
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOADS_FOLDER)
        run("Copying ${f.name} to Downloads", f) { p ->
            val copy = RecordingFiles.copy(f, dest, p)
            "Copied to ${copy.path}" + if (copy.isDirectory) " (with .nomedia)" else ""
        }
    }

    private fun share(f: File) {
        if (f.isDirectory) {
            Dialogs.confirm(this, "Only a zip can be shared. Zip ${f.name} (the folder is replaced by the zip) and share it?", "Zip and share") {
                run("Zipping ${f.name}", f, then = { zip -> zip?.let(::launchShare) }) { p ->
                    RecordingFiles.zip(f, p).also { result = it }.let { "Zipped to ${it.name}" }
                }
            }
            return
        }
        launchShare(f)
    }

    private fun launchShare(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData extends the read grant to the share sheet itself (its preview).
        send.clipData = ClipData.newRawUri(zip.name, uri)
        startActivity(Intent.createChooser(send, "Share ${zip.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private var result: File? = null
    private var busyFile: File? = null
    /** Progress of the running operation, 0..1 (negative: not known yet). */
    private var busyFraction = -1f
    private lateinit var topProgress: LinearProgressIndicator
    private var cardProgress: LinearProgressIndicator? = null

    /** Runs [work] in the background with progress; [then] gets [result] on the main thread. */
    private fun run(label: String, f: File, then: (File?) -> Unit = {}, work: ((Long, Long) -> Unit) -> String) {
        if (activeDir?.canonicalPath == f.canonicalPath) return
        busy = label
        busyFile = f
        busyFraction = -1f
        topProgress.isIndeterminate = true
        result = null
        refresh()
        var lastUpdate = 0L
        thread(name = "RecordingFiles") {
            val message = try {
                work { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 200) {
                        lastUpdate = now
                        val fraction = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val text = "$label… %.0f%% (%.0f / %.0f MB)".format(100.0 * fraction, done / 1e6, total / 1e6)
                        main.post { showProgress(text, fraction) }
                    }
                }
            } catch (e: Exception) {
                "⚠ $label failed: ${e.message}"
            }
            main.post {
                busy = null
                busyFile = null
                if (isDestroyed) return@post
                toast(message)
                refresh()
                then(result)
            }
        }
    }

    private fun showProgress(text: String, fraction: Float) {
        if (busy == null) return
        status.text = text
        busyFraction = fraction
        for (bar in listOfNotNull(topProgress, cardProgress)) {
            if (bar.isIndeterminate) {
                // Switching off indeterminate mode needs the bar hidden first.
                bar.visibility = View.INVISIBLE
                bar.isIndeterminate = false
                bar.visibility = View.VISIBLE
            }
            bar.setProgressCompat((fraction * 1000).toInt(), true)
        }
    }

    /** Renames a recording folder (and the name in its metadata.json) or a zip. */
    private fun rename(f: File) {
        val zipped = !f.isDirectory
        val current = if (zipped) f.name.removeSuffix(".zip") else f.name
        Dialogs.askText(this, "Rename recording", current, "Rename") { input ->
            val name = input.trim().replace('/', '_').removeSuffix(".zip")
            if (name.isEmpty() || name == current) return@askText
            if (name.endsWith(".part") || name.endsWith(".unzipping")) {
                toast("Names ending in .part or .unzipping are reserved")
                return@askText
            }
            val target = File(f.parentFile, if (zipped) "$name.zip" else name)
            val other = File(f.parentFile, if (zipped) name else "$name.zip")
            when {
                target.exists() -> toast("${target.name} already exists")
                other.exists() -> toast("${other.name} already exists")
                !f.renameTo(target) -> toast("Could not rename ${f.name}")
                else -> {
                    if (!zipped) runCatching {
                        val meta = File(target, "metadata.json")
                        if (meta.exists()) {
                            val json = org.json.JSONObject(meta.readText())
                            json.put("name", name)
                            meta.writeText(json.toString(2))
                        }
                    }
                    toast("Renamed to ${target.name}")
                    refresh()
                }
            }
        }
    }

    private fun showStorageInfo() {
        Dialogs.info(
            this, "Where recordings are stored",
            "Recordings are written to this app's own storage (${Recording.root(this).path}): the fastest place, " +
                "with no permission prompts and no media indexing.\n\n" +
                "• Over USB (MTP): Android/data/$packageName/files/recordings\n" +
                "• adb pull /sdcard/Android/data/$packageName/files/recordings\n" +
                "• To Downloads: copies to Download/$DOWNLOADS_FOLDER/, which other apps and file managers see and which survives " +
                "uninstalling this app; folders get a .nomedia file so galleries ignore the images.\n" +
                "• Zip: one file instead of thousands, much faster to copy over MTP and the only form that can be shared. " +
                "Images and video are stored uncompressed in the zip (they already are compressed), so zipping takes about " +
                "as long as copying.\n\n" +
                "Everything here is deleted when the app is uninstalled.",
        )
    }

    // endregion

    private fun secondary(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.panel_text_secondary))
    }

    private fun toast(text: CharSequence) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val DOWNLOADS_FOLDER = "XREAL Stereo"
        private const val EXTRA_ACTIVE = "active_dir"

        /** Opens the list; [activeDir] (being recorded) is shown but locked. */
        fun open(context: Context, activeDir: File?) {
            context.startActivity(Intent(context, RecordingsActivity::class.java).apply {
                activeDir?.let { putExtra(EXTRA_ACTIVE, it.absolutePath) }
            })
        }
    }
}
