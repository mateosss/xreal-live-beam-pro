package com.xreal.stereoview.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.xreal.stereoview.record.Dataset

/**
 * Decodes camera frames for the preview on a background thread. A request names one frame per
 * camera (the frames shown at one moment); they are decoded together and delivered together on
 * the main thread, so the cameras never appear out of step while one is still decoding. Only
 * the newest request is kept, so scrubbing never builds a backlog. Images are decoded at about
 * [maxWidth] pixels wide; videos by [VideoFrameDecoder], by frame index.
 */
class FrameLoader(private val cameras: List<Dataset.Camera>, private val onFrames: (List<Result>) -> Unit) {
    /** One camera's decoded frame, or null and why not. */
    class Result(val camera: Int, val frame: Int, val bitmap: Bitmap?, val error: String?)

    private val thread = HandlerThread("PreviewFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pending: IntArray? = null
    private val shown = IntArray(cameras.size) { -1 }
    private val decoders = arrayOfNulls<VideoFrameDecoder>(cameras.size)

    @Volatile var maxWidth = 640

    /** Shows frame [frames] [i] of camera i (negative: leave that camera as it is). */
    fun request(frames: IntArray) {
        synchronized(lock) {
            if (frames.indices.all { frames[it] < 0 || frames[it] == shown[it] } && pending == null) return
            val queued = pending != null
            pending = frames.copyOf()
            if (queued) return
        }
        handler.post(::decode)
    }

    private fun decode() {
        val frames = synchronized(lock) { pending.also { pending = null } } ?: return
        val results = ArrayList<Result>()
        for (camera in frames.indices) {
            val frame = frames[camera]
            if (frame < 0 || cameras[camera].timestamps.isEmpty() || synchronized(lock) { shown[camera] } == frame) continue
            val (bitmap, error) = decodeFrame(camera, frame)
            results += Result(camera, frame, bitmap, error)
        }
        synchronized(lock) { for (r in results) shown[r.camera] = r.frame }
        if (results.isNotEmpty()) main.post { onFrames(results) }
    }

    /** The frame, or null and why not (shown on the tile). */
    private fun decodeFrame(camera: Int, frame: Int): Pair<Bitmap?, String?> {
        val c = cameras[camera]
        return try {
            if (c.video != null) {
                val d = decoders[camera] ?: VideoFrameDecoder(c.video).also { decoders[camera] = it }
                if (frame >= d.frameCount) {
                    return null to "${c.video.name} has ${d.frameCount} frames but data.csv has ${c.timestamps.size} rows"
                }
                val b = d.frame(frame, maxWidth)
                b to (if (b == null) "Frame ${frame + 1} of ${c.video.name} could not be decoded" else null)
            } else {
                val file = c.imageFile(frame)
                if (!file.exists()) {
                    val name = c.files.getOrNull(frame).orEmpty()
                    return null to if (name.isEmpty() || name.endsWith(".png") || name.endsWith(".jpg")) {
                        "${c.name}: no data/$name and no data.mkv or data.mp4 in this folder"
                    } else {
                        "${c.name}: missing data/$name"
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                b to (if (b == null) "${file.name} could not be decoded" else null)
            }
        } catch (e: Exception) {
            Log.w("FrameLoader", "Frame $frame of camera $camera", e)
            null to "${c.name}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Forgets what is shown, e.g. after the image views were rebuilt, so the next request decodes again. */
    fun invalidate() {
        synchronized(lock) { shown.fill(-1) }
    }

    fun release() {
        handler.post {
            decoders.forEach { runCatching { it?.close() } }
            thread.quitSafely()
        }
    }
}
