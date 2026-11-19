package com.xreal.stereoview.record

import org.json.JSONObject

/**
 * How far apart the two images of synchronized stereo pairs are timestamped (right − left),
 * for metadata.json: datasets store both images under the left one's timestamp.
 */
class PairSpread {
    private var count = 0L
    private var sum = 0.0
    private var min = Long.MAX_VALUE
    private var max = Long.MIN_VALUE

    @Synchronized
    fun add(rightMinusLeftNs: Long) {
        count++
        sum += rightMinusLeftNs
        min = minOf(min, rightMinusLeftNs)
        max = maxOf(max, rightMinusLeftNs)
    }

    @Synchronized
    fun json(): JSONObject = JSONObject().apply {
        put("dataset_timestamp", "the left image's (cam0); both images of a pair use it in data.csv and file names")
        put("own_timestamps", "frames.csv sensor_timestamp_ns of each camera")
        put("pairs", count)
        if (count > 0) {
            put("right_minus_left_mean_us", sum / count / 1e3)
            put("right_minus_left_min_us", min / 1e3)
            put("right_minus_left_max_us", max / 1e3)
        }
    }
}
