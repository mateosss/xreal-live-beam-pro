package com.xreal.stereoview.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.xreal.stereoview.record.Dataset

/**
 * Decodes camera frames for the preview on a background thread. Only the newest request per
 * camera is kept, so scrubbing never builds a backlog; results arrive on the main thread.
 * Images are decoded at about [maxWidth] pixels wide; videos by [VideoFrameDecoder], by frame index.
 */
class FrameLoader(private val cameras: List<Dataset.Camera>, private val onFrame: (camera: Int, frame: Int, Bitmap?) -> Unit) {
    private val thread = HandlerThread("PreviewFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pending = arrayOfNulls<Int>(cameras.size)
    private val shown = IntArray(cameras.size) { -1 }
    private val decoders = arrayOfNulls<VideoFrameDecoder>(cameras.size)
    @Volatile var maxWidth = 640

    fun request(camera: Int, frame: Int) {
        if (cameras[camera].timestamps.isEmpty()) return
        synchronized(pending) {
            if (shown[camera] == frame && pending[camera] == null) return
            val queued = pending[camera] != null
            pending[camera] = frame
            if (queued) return
        }
        handler.post { decode(camera) }
    }

    private fun decode(camera: Int) {
        val frame = synchronized(pending) { pending[camera].also { pending[camera] = null } } ?: return
        val bitmap = decodeFrame(camera, frame)
        synchronized(pending) { shown[camera] = frame }
        main.post { onFrame(camera, frame, bitmap) }
    }

    private fun decodeFrame(camera: Int, frame: Int): Bitmap? {
        return try {
            val c = cameras[camera]
            if (c.video != null) {
                val d = decoders[camera] ?: VideoFrameDecoder(c.video).also { decoders[camera] = it }
                d.frame(frame, maxWidth)
            } else {
                val file = c.imageFile(frame)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            Log.w("FrameLoader", "Frame $frame of camera $camera", e)
            null
        }
    }

    /** Forgets what is shown, e.g. after the image views were rebuilt, so the next request decodes again. */
    fun invalidate() {
        synchronized(pending) { shown.fill(-1) }
    }

    fun release() {
        handler.post {
            decoders.forEach { runCatching { it?.close() } }
            thread.quitSafely()
        }
    }
}
