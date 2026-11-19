package com.xreal.stereoview.record

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread

/**
 * File operations on recordings (zip, unzip, copy, delete…), run one at a time in the order
 * they were asked for. Held by the process, not a screen: leaving the recordings list doesn't
 * stop or forget them. All state changes happen on the main thread.
 *
 * A job whose recording no longer exists when its turn comes (e.g. zipped by an earlier job)
 * is skipped with a message.
 */
object RecordingQueue {
    class Job(
        val label: String,
        val file: File,
        /** Runs on the main thread after the job, with the screen showing the queue (if any). */
        val then: ((Activity, File?) -> Unit)? = null,
        /** Does the work on a background thread; reports progress (done, total); returns the message and a resulting file. */
        val work: (progress: (Long, Long) -> Unit) -> Pair<String, File?>,
    )

    interface Listener {
        /** The queue changed: a job was added, started, finished or cancelled. */
        fun onQueueChanged()
        fun onProgress(text: String, fraction: Float)
        fun onJobDone(job: Job, message: String, result: File?)
        val activity: Activity
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Job>()
    var current: Job? = null
        private set
    /** Progress of [current], 0..1 (negative: not known yet), and its text. */
    var fraction = -1f
        private set
    var progressText: String? = null
        private set
    var listener: Listener? = null

    val queued: List<Job> get() = pending.toList()

    fun isBusy(f: File) = (listOfNotNull(current) + pending).any { it.file.canonicalPath == f.canonicalPath }

    /** The jobs waiting (not running) for [f]. */
    fun queuedFor(f: File) = pending.filter { it.file.canonicalPath == f.canonicalPath }

    fun add(job: Job) {
        pending.addLast(job)
        if (current == null) startNext() else listener?.onQueueChanged()
    }

    /** Removes a job that hasn't started. */
    fun cancel(job: Job) {
        if (pending.remove(job)) listener?.onQueueChanged()
    }

    private fun startNext() {
        val job = pending.removeFirstOrNull()
        current = job
        fraction = -1f
        progressText = null
        listener?.onQueueChanged()
        if (job == null) return
        var lastUpdate = 0L
        thread(name = "RecordingQueue") {
            val (message, result) = try {
                if (!job.file.exists()) "Skipped “${job.label}”: ${job.file.name} no longer exists" to null
                else job.work { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 200) {
                        lastUpdate = now
                        val f = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val text = "${job.label}… %.0f%% (%.0f / %.0f MB)".format(100.0 * f, done / 1e6, total / 1e6)
                        main.post {
                            if (current !== job) return@post
                            fraction = f
                            progressText = text
                            listener?.onProgress(text, f)
                        }
                    }
                }
            } catch (e: Exception) {
                "⚠ ${job.label} failed: ${e.message}" to null
            }
            main.post {
                listener?.let { l ->
                    l.onJobDone(job, message, result)
                    if (!l.activity.isDestroyed) job.then?.invoke(l.activity, result)
                }
                startNext()
            }
        }
    }
}
