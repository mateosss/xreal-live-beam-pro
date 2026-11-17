package com.xreal.stereoview.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.xreal.stereoview.record.Dataset

/**
 * Decodes camera frames for the preview on a background thread. Only the newest request per
 * camera is kept, so scrubbing never builds a backlog; results arrive on the main thread.
 * Images are decoded at about [maxWidth] pixels wide; videos are read by frame index.
 */
class FrameLoader(private val cameras: List<Dataset.Camera>, private val onFrame: (camera: Int, frame: Int, Bitmap?) -> Unit) {
    private val thread = HandlerThread("PreviewFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pending = arrayOfNulls<Int>(cameras.size)
    private val shown = IntArray(cameras.size) { -1 }
    private val retrievers = arrayOfNulls<MediaMetadataRetriever>(cameras.size)
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
        val bitmap = try {
            val c = cameras[camera]
            if (c.video != null) {
                val r = retrievers[camera] ?: MediaMetadataRetriever().also {
                    it.setDataSource(c.video.path)
                    retrievers[camera] = it
                }
                r.getFrameAtIndex(frame)?.let(::scaled)
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
        synchronized(pending) { shown[camera] = frame }
        main.post { onFrame(camera, frame, bitmap) }
    }

    private fun scaled(b: Bitmap): Bitmap =
        if (b.width <= maxWidth * 3 / 2) b else Bitmap.createScaledBitmap(b, maxWidth, b.height * maxWidth / b.width, true).also { b.recycle() }

    fun release() {
        handler.post {
            retrievers.forEach { runCatching { it?.release() } }
            thread.quitSafely()
        }
    }
}
