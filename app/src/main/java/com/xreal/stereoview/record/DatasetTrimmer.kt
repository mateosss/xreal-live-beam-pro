package com.xreal.stereoview.record

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Writes the part of a recording between two dataset timestamps as a new recording in the same
 * layout: every camera's data.csv rows and images (or its video, cut by [cutVideo]), imu0,
 * the extra per-frame files, the raw IMU streams (shifted onto their own clock when it differs)
 * and the descriptions, with metadata.json noting what was trimmed. The source is not changed.
 */
object DatasetTrimmer {
    /**
     * Cuts frames [first]..[last] (indices) of an MP4 into a new file without re-encoding.
     * Returns the first frame actually kept: the keyframe at or before [first].
     */
    fun interface VideoCutter {
        fun cut(source: File, target: File, first: Int, last: Int): Int
    }

    class Result(val dir: File, val startNs: Long, val endNs: Long, val notes: List<String>)

    fun trim(
        dataset: Dataset,
        startNs: Long,
        endNs: Long,
        target: File,
        cutVideo: VideoCutter?,
        progress: (Float) -> Unit = {},
    ): Result {
        require(endNs > startNs) { "The end must be after the start" }
        if (target.exists()) throw IOException("${target.name} already exists")
        val partial = File(target.parentFile, "${target.name}.part")
        partial.deleteRecursively()
        val notes = ArrayList<String>()
        var actualStart = startNs
        try {
            val src = dataset.dir
            val mav = File(partial, "mav0").apply { mkdirs() }
            File(src, "mav0/body.yaml").takeIf { it.exists() }?.copyTo(File(mav, "body.yaml"))
            val total = dataset.cameras.size + 2
            dataset.cameras.forEachIndexed { n, cam ->
                val out = File(mav, cam.name).apply { mkdirs() }
                File(cam.dir, "sensor.yaml").takeIf { it.exists() }?.copyTo(File(out, "sensor.yaml"))
                var first = cam.timestamps.indexOfFirst { it >= startNs }
                val last = cam.timestamps.indexOfLast { it <= endNs }
                if (first < 0 || last < first) {
                    File(out, "data.csv").writeText(Euroc.CAMERA_HEADER + "\n")
                    notes += "${cam.name}: no frames in the range"
                    return@forEachIndexed
                }
                val video = cam.video
                if (video != null) {
                    val cutter = cutVideo ?: throw IOException("Cutting video needs Android's media framework")
                    val kept = cutter.cut(video, File(out, "data.mp4"), first, last)
                    if (kept < first) {
                        notes += "${cam.name}: video starts at the keyframe %.3f s earlier".format((cam.timestamps[first] - cam.timestamps[kept]) / 1e9)
                        first = kept
                        actualStart = minOf(actualStart, cam.timestamps[kept])
                    }
                } else {
                    File(out, "data").mkdirs()
                    for (i in first..last) cam.imageFile(i).copyTo(File(out, "data/${cam.files[i]}"))
                }
                File(out, "data.csv").bufferedWriter().use { w ->
                    w.write(Euroc.CAMERA_HEADER + "\n")
                    for (i in first..last) w.write("${cam.timestamps[i]},${cam.files[i]}\n")
                }
                trimCameraExtras(cam.dir, out, cam.timestamps[first], cam.timestamps[last])
                progress((n + 1f) / total)
            }
            File(src, "mav0/imu0").takeIf { it.isDirectory }?.let { imu0 ->
                val out = File(mav, "imu0").apply { mkdirs() }
                File(imu0, "sensor.yaml").takeIf { it.exists() }?.copyTo(File(out, "sensor.yaml"))
                filterCsv(File(imu0, "data.csv"), File(out, "data.csv"), 0, actualStart, endNs)
            }
            progress((total - 1f) / total)
            File(src, "imu").listFiles { f -> f.name.endsWith(".csv") }?.let { streams ->
                val out = File(partial, "imu").apply { mkdirs() }
                for (f in streams) {
                    val offset = streamOffset(dataset, f.name)
                    if (offset == null) {
                        f.copyTo(File(out, f.name))
                        notes += "imu/${f.name}: copied whole (its clock offset is unknown)"
                    } else {
                        filterCsv(f, File(out, f.name), 0, actualStart + offset, endNs + offset)
                    }
                }
            }
            for (f in src.listFiles().orEmpty()) {
                if (f.isFile && f.name != "metadata.json") f.copyTo(File(partial, f.name))
            }
            val metadata = dataset.metadata?.let { JSONObject(it.toString()) } ?: JSONObject()
            metadata.put("name", target.name)
            metadata.put("trimmed_from", JSONObject().apply {
                put("recording", dataset.name)
                put("start_ns", actualStart)
                put("end_ns", endNs)
                put("requested_start_ns", startNs)
                put("offset_from_source_start_s", (actualStart - dataset.startNs) / 1e9)
                put("notes", org.json.JSONArray(notes))
                put("note", "Statistics under cameras[].stats and stats describe the source recording.")
            })
            File(partial, "metadata.json").writeText(metadata.toString(2))
            if (!partial.renameTo(target)) throw IOException("Cannot rename ${partial.name}")
            progress(1f)
        } catch (e: Exception) {
            partial.deleteRecursively()
            throw e
        }
        return Result(target, actualStart, endNs, notes)
    }

    /**
     * Offset from the dataset clock to the clock of raw stream [file] (added to dataset times),
     * or null if unknown. Phone streams are on CLOCK_BOOTTIME: the dataset clock for Beam Pro
     * recordings, the glasses' or OAK's own clock otherwise.
     */
    private fun streamOffset(dataset: Dataset, file: String): Long? {
        if (file.startsWith("glasses_") || file.startsWith("oak_")) return 0L
        val m = dataset.metadata
        return when (dataset.source) {
            null, "BEAM_PRO" -> 0L
            "GLASSES" -> m?.optJSONObject("glasses_clock")?.let {
                if (it.has("boottime_minus_glasses_ns_from_camera")) it.getLong("boottime_minus_glasses_ns_from_camera") else null
            }
            "OAK" -> m?.optJSONObject("oak_clock")?.let {
                if (it.has("boottime_minus_oak_ns_upper_bound")) it.getLong("boottime_minus_oak_ns_upper_bound") else null
            }
            else -> null
        }
    }

    /**
     * The other files of a camera folder: frames.csv by its dataset timestamp column (and, for
     * Beam Pro recordings, capture_results.csv by the matching sensor timestamps); anything
     * else whose first column is a timestamp is filtered by it.
     */
    private fun trimCameraExtras(src: File, out: File, firstNs: Long, lastNs: Long) {
        val frames = File(src, "frames.csv")
        var sensorRange: LongRange? = null
        if (frames.exists()) {
            val header = frames.bufferedReader().use { it.readLine() }.orEmpty().split(',')
            val tsColumn = header.indexOf("timestamp_ns").coerceAtLeast(0)
            val sensorColumn = header.indexOf("sensor_timestamp_ns")
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            filterCsv(frames, File(out, "frames.csv"), tsColumn, firstNs, lastNs) { parts ->
                if (sensorColumn >= 0) parts.getOrNull(sensorColumn)?.toLongOrNull()?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
            }
            if (sensorColumn >= 0 && lo <= hi) sensorRange = lo..hi
        }
        File(src, "capture_results.csv").takeIf { it.exists() }?.let { results ->
            val header = results.bufferedReader().use { it.readLine() }.orEmpty().split(',')
            val column = header.indexOf("timestamp_ns").coerceAtLeast(0)
            val range = sensorRange ?: (firstNs..lastNs)
            filterCsv(results, File(out, "capture_results.csv"), column, range.first, range.last)
        }
    }

    /** Copies the header and the rows whose [column] lies in [from]..[to]; [onKept] sees kept rows. */
    private fun filterCsv(src: File, out: File, column: Int, from: Long, to: Long, onKept: (List<String>) -> Unit = {}) {
        if (!src.exists()) return
        out.bufferedWriter().use { w ->
            var first = true
            src.forEachLine { line ->
                if (first) {
                    first = false
                    if (line.startsWith("#") || line.substringBefore(',').trim().toLongOrNull() == null) {
                        w.write(line)
                        w.write("\n")
                        return@forEachLine
                    }
                }
                val parts = line.split(',')
                val t = parts.getOrNull(column)?.trim()?.toLongOrNull() ?: return@forEachLine
                if (t in from..to) {
                    w.write(line)
                    w.write("\n")
                    onKept(parts)
                }
            }
        }
    }
}
