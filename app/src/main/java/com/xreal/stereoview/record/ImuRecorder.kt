package com.xreal.stereoview.record

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.MemoryFile
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.ui.ImuRate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A sensor stream that can be recorded, with its CSV file name and value columns. */
enum class ImuStream(val type: Int, val file: String, val columns: List<String>, val label: String) {
    ACCELEROMETER(Sensor.TYPE_ACCELEROMETER, "accelerometer", listOf("ax", "ay", "az"), "Accelerometer (m/s²)"),
    GYROSCOPE(Sensor.TYPE_GYROSCOPE, "gyroscope", listOf("wx", "wy", "wz"), "Gyroscope (rad/s)"),
    ACCELEROMETER_UNCALIBRATED(
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, "accelerometer_uncalibrated",
        listOf("ax", "ay", "az", "bias_x", "bias_y", "bias_z"), "Accelerometer, uncalibrated + bias",
    ),
    GYROSCOPE_UNCALIBRATED(
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED, "gyroscope_uncalibrated",
        listOf("wx", "wy", "wz", "drift_x", "drift_y", "drift_z"), "Gyroscope, uncalibrated + drift",
    ),
    MAGNETOMETER(Sensor.TYPE_MAGNETIC_FIELD, "magnetometer", listOf("mx", "my", "mz"), "Magnetometer (µT)"),
    MAGNETOMETER_UNCALIBRATED(
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, "magnetometer_uncalibrated",
        listOf("mx", "my", "mz", "bias_x", "bias_y", "bias_z"), "Magnetometer, uncalibrated + bias",
    ),
    GAME_ROTATION_VECTOR(Sensor.TYPE_GAME_ROTATION_VECTOR, "game_rotation_vector", listOf("qx", "qy", "qz", "qw"), "Game rotation vector (fused, no magnetometer)"),
    ROTATION_VECTOR(
        Sensor.TYPE_ROTATION_VECTOR, "rotation_vector",
        listOf("qx", "qy", "qz", "qw", "heading_accuracy_rad"), "Rotation vector (fused, with magnetometer)",
    ),
    GRAVITY(Sensor.TYPE_GRAVITY, "gravity", listOf("gx", "gy", "gz"), "Gravity (fused)"),
    LINEAR_ACCELERATION(Sensor.TYPE_LINEAR_ACCELERATION, "linear_acceleration", listOf("ax", "ay", "az"), "Linear acceleration (fused)"),
    PRESSURE(Sensor.TYPE_PRESSURE, "pressure", listOf("hpa"), "Barometer (hPa)"),
    ;

    companion object {
        fun parse(names: String): List<ImuStream> {
            val set = names.split(',').map { it.trim() }.toSet()
            return entries.filter { it.name in set }
        }
    }
}

/**
 * Records IMU streams into `<dir>/<stream>.csv` with columns
 * `timestamp_ns,arrival_ns,<values>,accuracy`. Timestamps are SensorEvent.timestamp
 * (CLOCK_BOOTTIME, the same base as SystemClock.elapsedRealtimeNanos); `arrival_ns` is that
 * clock when the app received the sample, which shows delivery latency and batching.
 *
 * With a direct-channel [ImuRate], sensors that support it are read from shared memory (rates
 * up to ~800 Hz with no listener overhead); the others fall back to a listener at full speed.
 */
class ImuRecorder(
    private val sensorManager: SensorManager,
    private val dir: File,
    streams: List<ImuStream>,
    private val rate: ImuRate,
) {
    private inner class Track(val stream: ImuStream, val sensor: Sensor) {
        val writer = LineWriter(
            File(dir, "${stream.file}.csv"),
            (listOf("timestamp_ns", "arrival_ns") + stream.columns + "accuracy").joinToString(","),
        )
        var mode = "listener"
        var requestedPeriodUs = 0
        var directToken = 0
        var count = 0L
        var firstNs = 0L
        var lastNs = 0L
        var maxGapNs = 0L
        var nonMonotonic = 0L
        val line = StringBuilder(160)

        fun write(timestampNs: Long, arrivalNs: Long, values: FloatArray, valueCount: Int, accuracy: Int?) {
            if (count == 0L) firstNs = timestampNs else {
                val gap = timestampNs - lastNs
                if (gap <= 0) nonMonotonic++ else if (gap > maxGapNs) maxGapNs = gap
            }
            lastNs = timestampNs
            count++
            line.setLength(0)
            line.append(timestampNs).append(',').append(arrivalNs)
            for (i in stream.columns.indices) {
                line.append(',')
                if (i < valueCount) line.append(values[i])
            }
            line.append(',')
            if (accuracy != null) line.append(accuracy)
            writer.line(line)
        }

        fun toJson() = JSONObject().apply {
            put("stream", stream.name)
            put("file", "imu/${stream.file}.csv")
            put("columns", JSONArray(listOf("timestamp_ns", "arrival_ns") + stream.columns + "accuracy"))
            put("mode", mode)
            put("requested_period_us", requestedPeriodUs)
            put("sensor", Metadata.sensor(sensor))
            put("samples", count)
            put("first_timestamp_ns", firstNs)
            put("last_timestamp_ns", lastNs)
            if (count > 1) put("mean_rate_hz", (count - 1) * 1e9 / (lastNs - firstNs))
            put("max_gap_ms", maxGapNs / 1e6)
            put("non_monotonic_samples", nonMonotonic)
        }
    }

    private val thread = HandlerThread("ImuRecorder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val tracks: List<Track>
    private val byType: Map<Int, Track>
    val missing: List<ImuStream>

    private var memory: MemoryFile? = null
    private var channel: SensorDirectChannel? = null
    private var reader: Thread? = null
    @Volatile
    private var reading = false
    private var directLost = 0L
    private var directResyncs = 0

    init {
        dir.mkdirs()
        val found = streams.map { it to sensorManager.getDefaultSensor(it.type) }
        tracks = found.mapNotNull { (stream, sensor) -> sensor?.let { Track(stream, it) } }
        missing = found.filter { it.second == null }.map { it.first }
        byType = tracks.associateBy { it.stream.type }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val arrival = SystemClock.elapsedRealtimeNanos()
            byType[event.sensor.type]?.write(event.timestamp, arrival, event.values, event.values.size, event.accuracy)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        val direct = if (rate.directRate > 0) {
            tracks.filter {
                it.sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) &&
                    it.sensor.highestDirectReportRateLevel >= rate.directRate
            }
        } else {
            emptyList()
        }
        if (direct.isNotEmpty()) startDirect(direct)
        for (track in tracks.filter { it.directToken <= 0 }) {
            // Direct-channel fallbacks run as fast as possible, like the channel would.
            val period = if (rate.directRate > 0) 0 else rate.periodUs
            track.requestedPeriodUs = maxOf(period, track.sensor.minDelay)
            track.mode = "listener"
            sensorManager.registerListener(listener, track.sensor, period, 0, handler)
        }
    }

    private fun startDirect(direct: List<Track>) {
        try {
            val memory = MemoryFile("imu-direct", EVENT_SIZE * SLOTS)
            val channel = sensorManager.createDirectChannel(memory)
            for (track in direct) {
                track.directToken = channel.configure(track.sensor, rate.directRate)
                if (track.directToken > 0) track.mode = "direct channel, rate level ${rate.directRate}"
            }
            this.memory = memory
            this.channel = channel
            if (direct.none { it.directToken > 0 }) return
            reading = true
            reader = Thread({ readDirect(memory) }, "ImuDirectReader").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct channel unavailable", e)
            direct.forEach { it.directToken = 0 }
        }
    }

    /**
     * Follows the channel's ring buffer: events are written in order, each with a counter that
     * increases by one per event (1, 2, …, wrapping past 2³²−1 back to 1).
     */
    private fun readDirect(memory: MemoryFile) {
        val byToken = tracks.filter { it.directToken > 0 }.associateBy { it.directToken }
        val bytes = ByteArray(EVENT_SIZE)
        val event = DirectEvent()
        var slot = 0
        var expected = 1L
        var idleSince = SystemClock.elapsedRealtime()
        while (reading) {
            memory.readBytes(bytes, slot * EVENT_SIZE, 0, EVENT_SIZE)
            DirectEvent.parse(bytes, event)
            when {
                event.counter == expected -> Unit
                event.counter != 0L && DirectEvent.isAfter(event.counter, expected) -> {
                    // The writer lapped us; skip ahead and count what was overwritten.
                    directLost += DirectEvent.distance(expected, event.counter)
                    directResyncs++
                }
                else -> {
                    if (SystemClock.elapsedRealtime() - idleSince > 1000) {
                        resync(memory, bytes, event)?.let { (s, c) ->
                            slot = s
                            expected = c
                        }
                        idleSince = SystemClock.elapsedRealtime()
                    }
                    Thread.sleep(1)
                    continue
                }
            }
            byToken[event.token]?.write(event.timestampNs, SystemClock.elapsedRealtimeNanos(), event.values, event.values.size, null)
            expected = DirectEvent.next(event.counter)
            slot = (slot + 1) % SLOTS
            idleSince = SystemClock.elapsedRealtime()
        }
    }

    /** Finds the newest event in the buffer, for when the expected position never fills. */
    private fun resync(memory: MemoryFile, bytes: ByteArray, event: DirectEvent): Pair<Int, Long>? {
        var best: Pair<Int, Long>? = null
        for (s in 0 until SLOTS) {
            memory.readBytes(bytes, s * EVENT_SIZE, 0, EVENT_SIZE)
            DirectEvent.parse(bytes, event)
            if (event.counter == 0L) continue
            if (best == null || DirectEvent.isAfter(event.counter, best.second)) best = s to event.counter
        }
        return best?.let { (s, c) ->
            directResyncs++
            (s + 1) % SLOTS to DirectEvent.next(c)
        }
    }

    /** Stops sampling and closes the files; returns the per-stream summary for the metadata. */
    fun stop(): JSONObject {
        sensorManager.unregisterListener(listener)
        reading = false
        reader?.join(500)
        channel?.let { ch ->
            tracks.filter { it.directToken > 0 }.forEach { ch.configure(it.sensor, SensorDirectChannel.RATE_STOP) }
            ch.close()
        }
        memory?.close()
        // Let queued listener callbacks finish before closing the files.
        val drained = CountDownLatch(1)
        handler.post { drained.countDown() }
        drained.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
        tracks.forEach { it.writer.close() }
        return JSONObject().apply {
            put("rate", rate.name)
            put("rate_label", rate.label)
            put("timestamp_clock", "CLOCK_BOOTTIME (SystemClock.elapsedRealtimeNanos)")
            put("frame", "Android sensor frame: x right, y up, z out of the screen, in the device's natural orientation")
            put("streams", JSONArray(tracks.map { it.toJson() }))
            put("missing_streams", JSONArray(missing.map { it.name }))
            if (channel != null) {
                put("direct_channel_lost_samples", directLost)
                put("direct_channel_resyncs", directResyncs)
            }
        }
    }

    private companion object {
        const val TAG = "ImuRecorder"
        const val EVENT_SIZE = DirectEvent.SIZE
        const val SLOTS = 4096
    }
}

/**
 * One event of a SensorDirectChannel buffer (104 bytes, little endian): size, report token,
 * type, atomic counter (uint32), timestamp (int64), 16 floats of data, 4 reserved ints.
 */
class DirectEvent {
    var token = 0
    var type = 0
    var counter = 0L
    var timestampNs = 0L
    val values = FloatArray(16)

    companion object {
        const val SIZE = 104

        fun parse(bytes: ByteArray, into: DirectEvent) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            into.token = b.getInt(4)
            into.type = b.getInt(8)
            into.counter = b.getInt(12).toLong() and 0xffffffffL
            into.timestampNs = b.getLong(16)
            for (i in 0 until 16) into.values[i] = b.getFloat(24 + 4 * i)
        }

        /** Counter after [c]; 0 is never used. */
        fun next(c: Long): Long = if (c >= 0xffffffffL) 1L else c + 1

        /** Steps from [from] forward to [to], with wrap-around. */
        fun distance(from: Long, to: Long): Long = if (to >= from) to - from else 0xffffffffL - from + to

        /** Whether [a] comes after [b] (within half the counter range). */
        fun isAfter(a: Long, b: Long): Boolean = a != b && distance(b, a) < 0x80000000L
    }
}
