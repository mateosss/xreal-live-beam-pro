package com.xreal.stereoview.preview

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xreal.stereoview.R
import com.xreal.stereoview.record.Dataset
import com.xreal.stereoview.record.DatasetTrimmer
import com.xreal.stereoview.ui.Dialogs
import java.io.File
import kotlin.concurrent.thread

/**
 * Plays back a saved recording: every camera, imu0's gyroscope and accelerometer curves, and a
 * timeline to drag, step or play along, with the current time and frame. Tabs switch between
 * this preview and per-sensor timestamp statistics; Trim selects a range on the timeline and
 * saves it as a new recording.
 *
 * Structure, for adding features: the top bar holds the tabs (one pane each) and actions; a
 * mode bar under it (trim) appears while a mode is active; the transport and timeline at the
 * bottom are shared by all panes.
 */
class RecordingPreviewActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private var dataset: Dataset? = null
    private var stats: List<Dataset.Stats> = emptyList()
    private var loader: FrameLoader? = null

    /** Cursor, relative to the recording's start. */
    private var positionNs = 0L
    private var playing = false
    private var tab = Tab.PREVIEW
    private var trimming = false
    private var imuWindowNs = 5_000_000_000L

    private enum class Tab { PREVIEW, STATISTICS }

    // Views, rebuilt on rotation.
    private lateinit var root: LinearLayout
    private var timeline: TimelineView? = null
    private var tiles: List<Pair<ImageView, TextView>> = emptyList()
    private var plots: List<ImuPlotView> = emptyList()
    private var timeText: TextView? = null
    private var frameText: TextView? = null
    private var playButton: MaterialButton? = null
    private var trimText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@RecordingPreviewActivity, R.color.home_background))
        }
        setContentView(root)
        positionNs = savedInstanceState?.getLong(KEY_POSITION) ?: 0L
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        showLoading(dir.name)
        thread(name = "LoadRecording") {
            val result = runCatching { Dataset.load(dir).let { it to it.stats() } }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { (d, s) ->
                    dataset = d
                    stats = s
                    loader = FrameLoader(d.cameras) { camera, frame, bitmap -> onFrameDecoded(camera, frame, bitmap) }
                    build()
                }.onFailure { e ->
                    Dialogs.info(this, "Cannot open ${dir.name}", e.message ?: e.toString())
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, positionNs)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (dataset != null) build()
    }

    override fun onPause() {
        setPlaying(false)
        super.onPause()
    }

    override fun onDestroy() {
        loader?.release()
        super.onDestroy()
    }

    private val portrait get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // region Layout

    private fun showLoading(name: String) {
        root.removeAllViews()
        root.gravity = Gravity.CENTER
        root.addView(ProgressBar(this))
        root.addView(label("Loading $name…", 14f).apply { gravity = Gravity.CENTER })
    }

    private fun build() {
        val d = dataset ?: return
        root.removeAllViews()
        root.gravity = Gravity.NO_GRAVITY
        root.addView(topBar(d))
        if (trimming) root.addView(trimBar())
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (tab == Tab.PREVIEW) content.addView(previewPane(d)) else content.addView(statsPane(d))
        root.addView(transport())
        root.addView(TimelineView(this).also { t ->
            t.durationNs = d.durationNs
            t.lanes = lanes()
            t.trimMode = trimming
            t.setTrim(trimStart, trimEnd)
            t.setPosition(positionNs)
            t.listener = object : TimelineView.Listener {
                override fun onSeek(timeNs: Long, fromUser: Boolean) {
                    if (fromUser) setPlaying(false)
                    seek(timeNs, fromTimeline = true)
                }

                override fun onTrimChanged(startNs: Long, endNs: Long) {
                    trimStart = startNs
                    trimEnd = endNs
                    updateTrimText()
                }
            }
            timeline = t
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        seek(positionNs)
    }

    private fun topBar(d: Dataset): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(12), dp(4))
        }
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            minWidth = 0
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(d.name, 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val drops = stats.sumOf { it.droppedEstimate }
        titles.addView(label(listOfNotNull(
            d.source?.replace('_', ' '),
            "%.1f s".format(d.durationNs / 1e9),
            "${d.cameras.size} camera${if (d.cameras.size == 1) "" else "s"}",
            if (d.imu0 != null) "imu0" else "no imu0",
            if (drops > 0) "⚠ $drops samples missing" else "no gaps",
        ).joinToString(" · "), 12f).apply {
            setTextColor(secondary)
            maxLines = 2
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val tabs = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for ((t, name) in listOf(Tab.PREVIEW to "Preview", Tab.STATISTICS to "Statistics")) {
            tabs.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = name
                isAllCaps = false
                tag = t
            })
        }
        tabs.check(tabs.getChildAt(tab.ordinal).id)
        tabs.addOnButtonCheckedListener { group, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = group.findViewById<View>(id).tag as Tab
            if (chosen != tab) {
                tab = chosen
                build()
            }
        }
        val trim = MaterialButton(this, null, if (trimming) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Trim"
            isAllCaps = false
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            setOnClickListener { setTrimming(!trimming) }
        }
        val trimParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        if (!portrait) {
            bar.addView(tabs)
            bar.addView(trim, trimParams)
            return bar
        }
        // Portrait: title row, then the tabs and actions on their own row.
        bar.addView(trim, trimParams)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
                bottomMargin = dp(4)
            })
        }
    }

    private fun previewPane(d: Dataset): View {
        val pane = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        // Cameras: one row in landscape (up to 3), two columns in portrait.
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val columns = when {
            d.cameras.size <= 1 -> 1
            portrait -> 2
            else -> minOf(d.cameras.size, 3)
        }
        val tileViews = ArrayList<Pair<ImageView, TextView>>()
        d.cameras.chunked(columns).forEach { rowCams ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in rowCams) {
                val tile = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                tile.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val caption = label(c.name, 11f).apply {
                    setBackgroundColor(Color.argb(150, 0, 0, 0))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    typeface = Typeface.MONOSPACE
                }
                tile.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                tileViews += image to caption
            }
            repeat(columns - rowCams.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        if (d.cameras.isEmpty()) grid.addView(label("No cameras in this recording", 14f))
        tiles = tileViews
        loader?.maxWidth = (resources.displayMetrics.widthPixels / columns.coerceAtLeast(1)).coerceAtMost(1280)

        // IMU: gyroscope and accelerometer, with the visible span.
        val imuColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (portrait) 0 else dp(8), if (portrait) dp(6) else 0, 0, 0)
        }
        val gyro = ImuPlotView(this, "gyroscope", "rad/s", 0..2)
        val accel = ImuPlotView(this, "accelerometer", "m/s²", 3..5)
        plots = listOf(gyro, accel)
        for (p in plots) {
            p.imu = d.imu0
            p.windowNs = imuWindowNs
            p.onScrub = { t ->
                setPlaying(false)
                seek(t - d.startNs)
            }
            imuColumn.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) })
        }
        val spans = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        for (seconds in listOf(1, 5, 20, 60)) {
            spans.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = "${seconds}s"
                tag = seconds * 1_000_000_000L
                minWidth = 0
                minimumWidth = 0
                isAllCaps = false
                if (seconds * 1_000_000_000L == imuWindowNs) spans.post { spans.check(id) }
            })
        }
        spans.addOnButtonCheckedListener { group, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            imuWindowNs = group.findViewById<View>(id).tag as Long
            plots.forEach { it.windowNs = imuWindowNs }
        }
        imuColumn.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("IMU span ", 12f).apply { setTextColor(secondary) })
            addView(spans)
        })

        if (portrait) {
            pane.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f))
            pane.addView(imuColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            pane.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
            pane.addView(imuColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        }
        return pane
    }

    private fun statsPane(d: Dataset): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        column.addView(label(
            "Δt is the time between consecutive samples. Missing samples are estimated from gaps longer than 1.5 × the median Δt " +
                "(each counts as Δt / median − 1). Writer drops are frames the app couldn't store in time. Tap a sensor to jump to its first gap.",
            12f,
        ).apply { setTextColor(secondary) })
        val table = TableLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        val header = listOf("Sensor", "Type", "Samples", "Rate", "Duration", "Δt min", "Δt median", "Δt max", "Missing", "Writer drops", "Out of order")
        table.addView(row(header, bold = true))
        for (s in stats) {
            val cells = listOf(
                s.name, s.kind, s.count.toString(),
                if (s.rateHz > 0) "%.1f Hz".format(s.rateHz) else "–",
                "%.2f s".format((s.lastNs - s.firstNs) / 1e9),
                ms(s.minDeltaNs), ms(s.medianDeltaNs), ms(s.maxDeltaNs),
                s.droppedEstimate.toString(),
                s.writerDrops?.toString() ?: "–",
                s.nonMonotonic.toString(),
            )
            table.addView(row(cells, bold = false, warn = setOf(8).filter { s.droppedEstimate > 0 } +
                listOfNotNull(9.takeIf { (s.writerDrops ?: 0) > 0 }, 10.takeIf { s.nonMonotonic > 0 })).apply {
                setBackgroundResource(TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
                setOnClickListener { jumpToFirstGap(s) }
            })
        }
        if (portrait) {
            // Narrow: one block per sensor, every number visible without scrolling sideways.
            for (st in stats) {
                column.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(4))
                    setBackgroundResource(TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
                    setOnClickListener { jumpToFirstGap(st) }
                    addView(label("${st.name}  ·  ${st.kind}", 14f).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(label("${st.count} samples · %.1f Hz · %.2f s".format(st.rateHz, (st.lastNs - st.firstNs) / 1e9), 12f).apply { setTextColor(secondary) })
                    addView(label("Δt min ${ms(st.minDeltaNs)} · median ${ms(st.medianDeltaNs)} · max ${ms(st.maxDeltaNs)}", 12f).apply {
                        setTextColor(secondary)
                        typeface = Typeface.MONOSPACE
                    })
                    val problems = "missing ${st.droppedEstimate} · writer drops ${st.writerDrops?.toString() ?: "–"} · out of order ${st.nonMonotonic}"
                    addView(label(problems, 12f).apply {
                        setTextColor(if (st.droppedEstimate > 0 || (st.writerDrops ?: 0) > 0 || st.nonMonotonic > 0) TimelineView.TRIM else secondary)
                    })
                })
            }
        } else {
            column.addView(HorizontalScrollView(this).apply { addView(table) })
        }
        d.metadata?.optJSONObject("trimmed_from")?.let { t ->
            column.addView(label("Trimmed from ${t.optString("recording")} at +%.3f s.".format(t.optDouble("offset_from_source_start_s")), 12f).apply {
                setTextColor(secondary)
                setPadding(0, dp(8), 0, 0)
            })
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun row(cells: List<String>, bold: Boolean, warn: List<Int> = emptyList()) = TableRow(this).apply {
        cells.forEachIndexed { i, c ->
            addView(label(c, 13f).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                if (i in warn) setTextColor(TimelineView.TRIM)
                gravity = if (i >= 2) Gravity.END else Gravity.START
            })
        }
    }

    private fun transport(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        fun button(text: String, description: String, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            contentDescription = description
            minWidth = 0
            isAllCaps = false
            setOnClickListener { action() }
        }.also { bar.addView(it) }
        button("⏮", "Previous frame") { step(-1) }
        playButton = button("▶", "Play") { setPlaying(!playing) }
        button("⏭", "Next frame") { step(1) }
        button("Next gap ›", "Jump to the next missing samples") { nextGap() }
        timeText = label("", 14f).apply { typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(12), 0) }
        frameText = label("", 12f).apply { setTextColor(secondary); typeface = Typeface.MONOSPACE }
        if (!portrait) {
            bar.addView(timeText)
            bar.addView(frameText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return bar
        }
        // Portrait: the time and frame on their own line above the buttons.
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@RecordingPreviewActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), 0)
                addView(timeText)
                addView(frameText)
            })
            addView(bar)
        }
    }

    private fun trimBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(12), dp(2))
            setBackgroundColor(Color.argb(40, 0xFF, 0xB7, 0x4D))
        }
        trimText = label("", 13f)
        if (!portrait) bar.addView(trimText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun button(text: String, filled: Boolean = false, action: () -> Unit) = bar.addView(MaterialButton(
            this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.borderlessButtonStyle,
        ).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        })
        button("Start here") { setTrimEdge(start = true) }
        button("End here") { setTrimEdge(start = false) }
        button("Cancel") { setTrimming(false) }
        button("Save as new…", filled = true) { saveTrim() }
        updateTrimText()
        if (portrait) {
            // Narrow: the selection on one line, the buttons under it.
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(40, 0xFF, 0xB7, 0x4D))
                addView(trimText!!.apply { setPadding(dp(16), dp(6), dp(16), 0) })
                bar.background = null
                addView(HorizontalScrollView(this@RecordingPreviewActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(bar)
                })
            }
        }
        return HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    // endregion

    // region Playback and seeking

    private fun seek(relativeNs: Long, fromTimeline: Boolean = false) {
        val d = dataset ?: return
        positionNs = relativeNs.coerceIn(0, d.durationNs)
        val t = d.startNs + positionNs
        if (!fromTimeline) timeline?.setPosition(positionNs)
        plots.forEach { it.cursorNs = t }
        d.cameras.forEachIndexed { i, c ->
            if (c.timestamps.isEmpty()) return@forEachIndexed
            val f = c.frameAt(t)
            loader?.request(i, f)
            tiles.getOrNull(i)?.second?.text = "${c.name}  ${f + 1}/${c.timestamps.size}  +%.3f s".format((c.timestamps[f] - d.startNs) / 1e9)
        }
        timeText?.text = "%.3f s / %.3f s".format(positionNs / 1e9, d.durationNs / 1e9)
        frameText?.text = d.cameras.firstOrNull()?.let { c ->
            if (c.timestamps.isEmpty()) null else "${c.name} frame ${c.frameAt(t) + 1} / ${c.timestamps.size}"
        } ?: ""
    }

    private fun onFrameDecoded(camera: Int, frame: Int, bitmap: Bitmap?) {
        tiles.getOrNull(camera)?.first?.setImageBitmap(bitmap)
    }

    /** Moves to the previous or next frame of the first camera. */
    private fun step(direction: Int) {
        val d = dataset ?: return
        setPlaying(false)
        val c = d.cameras.firstOrNull { it.timestamps.isNotEmpty() } ?: return
        val f = (c.frameAt(d.startNs + positionNs) + direction).coerceIn(0, c.timestamps.size - 1)
        seek(c.timestamps[f] - d.startNs)
    }

    private fun setPlaying(on: Boolean) {
        val d = dataset
        if (on && d != null && positionNs >= d.durationNs) seek(0)
        playing = on && d != null
        playButton?.text = if (playing) "⏸" else "▶"
        if (playing) {
            lastTick = SystemClock.elapsedRealtimeNanos()
            main.post(tick)
        } else {
            main.removeCallbacks(tick)
        }
    }

    private var lastTick = 0L
    private val tick = object : Runnable {
        override fun run() {
            val d = dataset ?: return
            if (!playing) return
            val now = SystemClock.elapsedRealtimeNanos()
            seek(positionNs + (now - lastTick))
            lastTick = now
            if (positionNs >= d.durationNs) setPlaying(false) else main.postDelayed(this, 33)
        }
    }

    private fun lanes(): List<TimelineView.Lane> {
        val d = dataset ?: return emptyList()
        val colors = listOf(Color.rgb(0xEF, 0x53, 0x50), Color.rgb(0xFF, 0xA7, 0x26), Color.rgb(0xAB, 0x47, 0xBC), Color.rgb(0x26, 0xC6, 0xDA))
        return stats.filter { it.name.startsWith("cam") || it.name == "imu0" }.mapIndexed { i, s ->
            TimelineView.Lane(s.name, colors[i % colors.size], LongArray(s.gapStarts.size) { s.gapStarts[it] - d.startNs })
        }
    }

    private fun nextGap() {
        val d = dataset ?: return
        setPlaying(false)
        val next = lanes().flatMap { it.gapsNs.asList() }.filter { it > positionNs + 1_000_000 }.minOrNull()
        if (next == null) {
            toast("No more gaps after this point")
            return
        }
        seek(next)
    }

    private fun jumpToFirstGap(s: Dataset.Stats) {
        val d = dataset ?: return
        val first = s.gapStarts.firstOrNull()
        tab = Tab.PREVIEW
        build()
        if (first == null) {
            toast("${s.name}: no gaps")
        } else if (s.name.startsWith("imu/")) {
            toast("${s.name} is on its own clock; its gaps aren't on this timeline")
        } else {
            seek(first - d.startNs)
        }
    }

    // endregion

    // region Trim

    private var trimStart = 0L
    private var trimEnd = 0L

    private fun setTrimming(on: Boolean) {
        val d = dataset ?: return
        setPlaying(false)
        if (on && !trimming) {
            trimStart = 0
            trimEnd = d.durationNs
        }
        trimming = on
        build()
    }

    private fun setTrimEdge(start: Boolean) {
        if (start) trimStart = minOf(positionNs, trimEnd) else trimEnd = maxOf(positionNs, trimStart)
        timeline?.setTrim(trimStart, trimEnd)
        updateTrimText()
    }

    private fun updateTrimText() {
        val video = dataset?.cameras?.any { it.video != null } == true
        trimText?.text = "Keep %.2f s → %.2f s  (%.2f s)".format(trimStart / 1e9, trimEnd / 1e9, (trimEnd - trimStart) / 1e9) +
            if (video) "  · video starts at a keyframe" else ""
    }

    private fun saveTrim() {
        val d = dataset ?: return
        if (trimEnd - trimStart < 100_000_000L) {
            toast("Select at least 0.1 s")
            return
        }
        val suggestion = "${d.name}_trim_%.1f-%.1fs".format(trimStart / 1e9, trimEnd / 1e9)
        Dialogs.askText(this, "Save the selection as a new recording", suggestion, "Save") { name ->
            val target = File(d.dir.parentFile, name.replace('/', '_'))
            if (target.exists()) {
                toast("$name already exists")
                return@askText
            }
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; setPadding(dp(24), dp(8), dp(24), 0) }
            val dialog = MaterialAlertDialogBuilder(this).setTitle("Trimming…").setView(progress).setCancelable(false).show()
            thread(name = "Trim") {
                val result = runCatching {
                    DatasetTrimmer.trim(d, d.startNs + trimStart, d.startNs + trimEnd, target, Mp4Cutter) { p ->
                        main.post { progress.progress = (p * 100).toInt() }
                    }
                }
                main.post {
                    dialog.dismiss()
                    result.onSuccess { r ->
                        setResult(RESULT_OK)
                        setTrimming(false)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Saved ${r.dir.name}")
                            .setMessage("%.2f s kept.".format((r.endNs - r.startNs) / 1e9) +
                                if (r.notes.isEmpty()) "" else "\n\n" + r.notes.joinToString("\n"))
                            .setPositiveButton("Open it") { _, _ -> open(this, r.dir) }
                            .setNegativeButton("Stay here", null)
                            .show()
                    }.onFailure { e ->
                        Dialogs.info(this, "Trim failed", e.message ?: e.toString())
                    }
                }
            }
        }
    }

    // endregion

    private val secondary get() = ContextCompat.getColor(this, R.color.panel_text_secondary)

    private fun label(text: String, sp: Float) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
    }

    private fun ms(ns: Long) = "%.2f ms".format(ns / 1e6)

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_DIR = "dir"
        private const val KEY_POSITION = "position"

        fun open(context: Context, dir: File) {
            context.startActivity(Intent(context, RecordingPreviewActivity::class.java).putExtra(EXTRA_DIR, dir.absolutePath))
        }
    }
}
