package com.xreal.stereoview.record

import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/**
 * A saved recording read back for preview, statistics and trimming: its cameras
 * (mav0/camN/data.csv with images in data/ or a data.mp4), mav0/imu0 and every raw IMU stream
 * under imu/. Plain java.io and org.json, so it is unit-tested on the JVM.
 *
 * Cameras and imu0 share the dataset clock; [startNs]..[endNs] spans them. Raw streams in imu/
 * may be on another clock (the phone's, for glasses and OAK recordings), so they only appear in
 * the statistics.
 */
class Dataset(
    val dir: File,
    val metadata: JSONObject?,
    val cameras: List<Camera>,
    val imu0: Imu?,
    /** Raw IMU streams (imu/<name>.csv): name and timestamps. */
    val streams: List<Pair<String, LongArray>>,
) {
    val name: String get() = dir.name
    /** BEAM_PRO, GLASSES or OAK (null for recordings older than format 2). */
    val source: String? get() = metadata?.optString("source")?.ifEmpty { null }

    val startNs: Long = (cameras.mapNotNull { it.timestamps.firstOrNull() } + listOfNotNull(imu0?.t?.firstOrNull())).minOrNull() ?: 0L
    val endNs: Long = (cameras.mapNotNull { it.timestamps.lastOrNull() } + listOfNotNull(imu0?.t?.lastOrNull())).maxOrNull() ?: 0L
    val durationNs: Long get() = endNs - startNs

    class Camera(
        val name: String,
        val dir: File,
        /** Dataset timestamps, ascending; row i of data.csv. */
        val timestamps: LongArray,
        val files: List<String>,
    ) {
        /** data.mp4 for video recordings (frame i = row i), else null and images are in data/. */
        val video: File? = File(dir, "data.mp4").takeIf { it.exists() }
        val format: String get() = if (video != null) "video" else files.firstOrNull()?.substringAfterLast('.') ?: "?"

        fun imageFile(index: Int): File = File(dir, "data/${files[index]}")

        /** The frame shown at [timeNs]: the last one at or before it (the first before any). */
        fun frameAt(timeNs: Long): Int {
            var i = timestamps.binarySearch(timeNs)
            if (i < 0) i = -i - 2
            return i.coerceIn(0, (timestamps.size - 1).coerceAtLeast(0))
        }
    }

    /** imu0: gyroscope (rad/s) in [values] 0..2, accelerometer (m/s²) in 3..5. */
    class Imu(val t: LongArray, val values: Array<FloatArray>) {
        fun indexAt(timeNs: Long): Int {
            var i = t.binarySearch(timeNs)
            if (i < 0) i = -i - 2
            return i.coerceIn(0, (t.size - 1).coerceAtLeast(0))
        }
    }

    /** Timestamp statistics of one sensor stream. */
    class Stats(
        val name: String,
        val kind: String,
        val count: Int,
        val firstNs: Long,
        val lastNs: Long,
        val minDeltaNs: Long,
        val medianDeltaNs: Long,
        val maxDeltaNs: Long,
        /** Samples missing from gaps longer than 1.5 median periods (Δ / median − 1 each). */
        val droppedEstimate: Long,
        /** Timestamps after which such a gap starts (at most [MAX_GAPS]). */
        val gapStarts: LongArray,
        /** Deltas ≤ 0: repeated or out-of-order timestamps. */
        val nonMonotonic: Int,
        /** Drops the recorder itself counted (writer queue full), if it recorded them. */
        val writerDrops: Int?,
    ) {
        val rateHz: Double get() = if (count > 1 && lastNs > firstNs) (count - 1) * 1e9 / (lastNs - firstNs) else 0.0
    }

    /** Statistics of every camera, imu0 and raw stream. */
    fun stats(): List<Stats> = buildList {
        for (c in cameras) add(statsOf(c.name, "camera (${c.format})", c.timestamps, writerDrops(c)))
        imu0?.let { add(statsOf("imu0", "EuRoC IMU", it.t, null)) }
        for ((name, t) in streams) add(statsOf("imu/$name", "raw IMU stream", t, null))
    }

    /** Frames the recorder reported dropping for [camera], from metadata.json. */
    private fun writerDrops(camera: Camera): Int? {
        val m = metadata ?: return null
        val index = camera.name.removePrefix("cam").toIntOrNull()
        val cams = m.optJSONArray("cameras")
        val perCamera = (0 until (cams?.length() ?: 0)).map { cams!!.getJSONObject(it) }
            .firstOrNull { it.optString("name") == camera.name || (index != null && it.optInt("index", -1) == index) }
            ?.optJSONObject("stats")?.let { s -> if (s.has("images_dropped")) s.getInt("images_dropped") else null }
        if (perCamera != null) return perCamera
        // Glasses and OAK recordings count drops for all cameras together.
        val stats = m.optJSONObject("stats") ?: return null
        return when {
            stats.has("frames_dropped_by_encoder") -> stats.getInt("frames_dropped_by_encoder")
            stats.has("frames_dropped_by_writer") -> stats.getInt("frames_dropped_by_writer")
            else -> null
        }
    }

    companion object {
        const val MAX_GAPS = 5000

        fun statsOf(name: String, kind: String, t: LongArray, writerDrops: Int?): Stats {
            if (t.size < 2) {
                return Stats(name, kind, t.size, t.firstOrNull() ?: 0, t.lastOrNull() ?: 0, 0, 0, 0, 0, LongArray(0), 0, writerDrops)
            }
            val d = LongArray(t.size - 1) { t[it + 1] - t[it] }
            val sorted = d.copyOf().also { it.sort() }
            val median = sorted[sorted.size / 2]
            var dropped = 0L
            var nonMonotonic = 0
            val gaps = ArrayList<Long>()
            for (i in d.indices) {
                if (d[i] <= 0) nonMonotonic++
                if (median > 0 && d[i] > median * 3 / 2) {
                    dropped += ((d[i].toDouble() / median).roundToLong() - 1).coerceAtLeast(1)
                    if (gaps.size < MAX_GAPS) gaps += t[i]
                }
            }
            return Stats(name, kind, t.size, t.first(), t.last(), sorted.first(), median, sorted.last(), dropped, gaps.toLongArray(), nonMonotonic, writerDrops)
        }

        /** Reads the recording in [dir] (a folder in the EuRoC layout, format_version 2). */
        fun load(dir: File): Dataset {
            val metadata = runCatching { JSONObject(File(dir, "metadata.json").readText()) }.getOrNull()
            val mav = File(dir, "mav0")
            val cameras = mav.listFiles { f -> f.isDirectory && f.name.matches(Regex("cam\\d+")) }.orEmpty()
                .sortedBy { it.name.removePrefix("cam").toInt() }
                .mapNotNull { camDir ->
                    val csv = File(camDir, "data.csv").takeIf { it.exists() } ?: return@mapNotNull null
                    val ts = LongList()
                    val files = ArrayList<String>()
                    csv.forEachLine { line ->
                        if (line.isEmpty() || line[0] == '#') return@forEachLine
                        val comma = line.indexOf(',')
                        if (comma < 0) return@forEachLine
                        val t = line.substring(0, comma).trim().toLongOrNull() ?: return@forEachLine
                        ts.add(t)
                        files += line.substring(comma + 1).trim()
                    }
                    Camera(camDir.name, camDir, ts.toArray(), files)
                }
            val imu0 = File(mav, "imu0/data.csv").takeIf { it.exists() }?.let(::readImu)
            val streams = File(dir, "imu").listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
                .map { it.name.removeSuffix(".csv") to readFirstColumn(it) }
                .filter { it.second.isNotEmpty() }
            return Dataset(dir, metadata, cameras, imu0, streams)
        }

        private fun readImu(file: File): Imu {
            val t = LongList()
            val v = Array(6) { FloatList() }
            file.forEachLine { line ->
                if (line.isEmpty() || line[0] == '#') return@forEachLine
                val parts = line.split(',')
                if (parts.size < 7) return@forEachLine
                val ts = parts[0].trim().toLongOrNull() ?: return@forEachLine
                t.add(ts)
                for (k in 0 until 6) v[k].add(parts[k + 1].trim().toFloatOrNull() ?: Float.NaN)
            }
            return Imu(t.toArray(), Array(6) { v[it].toArray() })
        }

        /** The first column of a CSV with a header line (raw IMU streams: timestamp_ns). */
        private fun readFirstColumn(file: File): LongArray {
            val t = LongList()
            file.forEachLine { line ->
                val comma = line.indexOf(',')
                val first = if (comma < 0) line else line.substring(0, comma)
                first.trim().toLongOrNull()?.let(t::add)
            }
            return t.toArray()
        }
    }
}

/** Growable primitive arrays, to read long CSVs without boxing. */
internal class LongList {
    private var a = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): LongArray = a.copyOf(size)
}

internal class FloatList {
    private var a = FloatArray(1024)
    private var size = 0

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): FloatArray = a.copyOf(size)
}
