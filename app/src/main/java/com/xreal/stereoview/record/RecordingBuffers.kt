package com.xreal.stereoview.record

/**
 * Sizes of the recording buffers, set in seconds (Settings → Recording buffers) and turned
 * into frames here. Camera backlogs live in the app's memory, so together they are capped at
 * [memoryCapBytes]: past that Android would kill the app, and the whole recording with it.
 */
object RecordingBuffers {
    /** App memory (Java heap limit) that all camera backlogs of one recording may take together. */
    fun memoryCapBytes(): Long = Runtime.getRuntime().maxMemory() * 6 / 10

    fun heapLimitBytes(): Long = Runtime.getRuntime().maxMemory()

    /**
     * Frames for [seconds] of one stream at [fps], [frameBytes] each, when [streams] such
     * streams share [memoryCapBytes]; at least [min].
     */
    fun frames(seconds: Int, fps: Int, frameBytes: Long, streams: Int, min: Int = 8): Int {
        val wanted = seconds.toLong() * fps
        val cap = memoryCapBytes() / streams.coerceAtLeast(1) / frameBytes.coerceAtLeast(1)
        return minOf(wanted, cap).coerceIn(min.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** Seconds actually buffered by [frames] frames at [fps]. */
    fun seconds(frames: Int, fps: Int): Double = frames.toDouble() / fps.coerceAtLeast(1)
}
