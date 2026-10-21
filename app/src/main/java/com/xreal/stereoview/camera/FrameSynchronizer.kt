package com.xreal.stereoview.camera

import android.os.Handler
import android.os.HandlerThread

/**
 * Pairs left/right frame arrivals so both eyes are latched in the same draw.
 *
 * A draw is requested once both eyes have a new frame, or when [timeoutMs] passes after the
 * first eye's frame (so a slow or stalled camera never freezes the other eye). The renderer
 * reports the timestamp delta of every pair it latches through [recordPair], which tracks how
 * many pairs exceeded the threshold.
 */
class FrameSynchronizer(private val onPairReady: () -> Unit) {
    private val thread = HandlerThread("FrameSync").apply { start() }

    /** Handler for SurfaceTexture.OnFrameAvailableListener callbacks. */
    val handler = Handler(thread.looper)

    @Volatile
    var timeoutMs: Long = 33

    private val pending = BooleanArray(2)
    private var timeoutPosted = false // only touched on [handler]'s thread
    private val timeout = Runnable {
        timeoutPosted = false
        onPairReady()
    }

    private var pairs = 0
    private var unsyncedPairs = 0
    private var deltaSumMs = 0.0
    private var deltaMaxMs = 0.0

    /** Must be called on [handler]'s thread. */
    fun onFrameAvailable(eye: Int) {
        val bothReady = synchronized(this) {
            pending[eye] = true
            pending[0] && pending[1]
        }
        if (bothReady) {
            handler.removeCallbacks(timeout)
            timeoutPosted = false
            onPairReady()
        } else if (!timeoutPosted) {
            timeoutPosted = true
            handler.postDelayed(timeout, timeoutMs)
        }
    }

    /** Returns which eyes have a new frame since the last call and clears the flags. */
    fun consume(): BooleanArray = synchronized(this) {
        val fresh = pending.copyOf()
        pending.fill(false)
        fresh
    }

    @Synchronized
    fun recordPair(deltaMs: Double) {
        pairs++
        deltaSumMs += deltaMs
        if (deltaMs > deltaMaxMs) deltaMaxMs = deltaMs
        if (deltaMs > timeoutMs) unsyncedPairs++
    }

    data class Stats(val pairs: Int, val unsyncedPairs: Int, val meanDeltaMs: Double, val maxDeltaMs: Double)

    /** Stats since the previous call. */
    @Synchronized
    fun takeStats(): Stats {
        val stats = Stats(pairs, unsyncedPairs, if (pairs > 0) deltaSumMs / pairs else 0.0, deltaMaxMs)
        pairs = 0
        unsyncedPairs = 0
        deltaSumMs = 0.0
        deltaMaxMs = 0.0
        return stats
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
