package com.xreal.stereoview.oak

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Luxonis OAK camera (e.g. OAK-D Pro W) over USB through depthai-core: the left and right
 * mono cameras (raw 8-bit), optionally the color camera as JPEG (encoded on the camera) or as
 * raw NV12 frames, and its IMU. Each camera has its own exposure control. Timestamps ([Mono.timestampNs] etc.) are on the camera's own clock, shared by
 * cameras and IMU; [Mono.hostNs] is the same instant on the phone's CLOCK_MONOTONIC as
 * estimated by depthai, [Mono.arrivalNs] when it reached the app on CLOCK_BOOTTIME.
 *
 * Callbacks arrive on the poll threads.
 */
class OakDevice(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(message: String)
        fun onError(message: String)

        /** Streaming; [info] is depthai's description of the device, with its calibration. */
        fun onStarted(info: JSONObject)
        fun onStereoFrame(frame: StereoFrame)
        fun onRgb(frame: Rgb)
        fun onImu(samples: List<ImuSample>)
    }

    data class Config(
        val monoHeight: Int,
        /** Mono frame rate. */
        val fps: Int,
        /** Color camera height (800 or 720), or 0 for none. */
        val rgbHeight: Int,
        val jpegQuality: Int,
        /** Gyroscope rate in Hz, or 0 for no IMU; the accelerometer runs at [accelRateFor] it. */
        val imuRateHz: Int,
        val usb3: Boolean,
        /** Per camera: cam0 (left), cam1 (right), cam2 (color). */
        val exposures: List<Exposure>,
        /** Frames per camera that may wait on the phone's side of the USB link (native memory). */
        val linkFrames: Int = MIN_LINK_FRAMES,
        /** IMU packets (one sample or more each) that may wait there. */
        val imuPackets: Int = MIN_IMU_PACKETS,
        /** Color frame rate (below 1: one frame every few seconds), or 0 for [fps]. */
        val rgbFps: Float = 0f,
        /** Color frames as raw NV12 (for PNG or the phone's encoder) instead of the camera's JPEGs. */
        val rgbRaw: Boolean = false,
    ) {
        val fpsPlan: FpsPlan get() = FpsPlan.of(fps, if (rgbHeight > 0) rgbFps else 0f)
    }

    /**
     * How the mono and color frame rates are produced. The cameras' capture times are kept
     * aligned by the OAK's firmware only while all sensors run at the same rate, so when one
     * rate is a multiple of the other, every sensor runs at the faster rate ([sensorFps]) and
     * the slower stream keeps every [monoEvery]th / [rgbEvery]th frame, on the camera (by
     * sequence number, so left and right stay paired): the kept frames remain synchronized.
     * Otherwise ([synchronized] false) the color sensor runs freely at its own rate. Color rates
     * that a sensor can't run at (below 1 fps, or fractional) always keep 1 in N frames of the
     * mono rate: [rgbFps] is then the rate that gives (e.g. 0.5 at 30 fps: 1 in 60).
     */
    data class FpsPlan(
        val monoFps: Int, val rgbFps: Float, val monoSensorFps: Int, val rgbSensorFps: Int,
        val monoEvery: Int, val rgbEvery: Int, val synchronized: Boolean,
    ) {
        companion object {
            /** The plan for [mono] fps mono and [rgb] fps color (0: the mono rate). */
            fun of(mono: Int, rgb: Float): FpsPlan {
                val m = mono.coerceAtLeast(1)
                val r = if (rgb > 0f) rgb else m.toFloat()
                val whole = r >= 1f && r == r.roundToInt().toFloat()
                if (r <= m) {
                    val ratio = m / r
                    val every = ratio.roundToInt().coerceAtLeast(1)
                    return if (abs(ratio - every) < 1e-3f || !whole) {
                        FpsPlan(m, m.toFloat() / every, m, m, 1, every, true)
                    } else {
                        FpsPlan(m, r, m, r.roundToInt(), 1, 1, false)
                    }
                }
                // Faster color: sensors only run at whole rates.
                val hi = r.roundToInt()
                return if (hi % m == 0) FpsPlan(m, hi.toFloat(), hi, hi, hi / m, 1, true) else FpsPlan(m, hi.toFloat(), m, hi, 1, 1, false)
            }

            fun of(mono: Int, rgb: Int) = of(mono, rgb.toFloat())
        }
    }

    /** One camera's exposure: auto (with compensation −9..9) or manual exposure time and ISO. */
    data class Exposure(val auto: Boolean = true, val exposureUs: Int = 4000, val iso: Int = 400, val compensation: Int = 0) {
        /** Within what the OAK sensors accept (the frame time limits the exposure further). */
        fun clamped() = copy(exposureUs = exposureUs.coerceIn(MIN_EXPOSURE_US, MAX_EXPOSURE_US), iso = iso.coerceIn(MIN_ISO, MAX_ISO),
            compensation = compensation.coerceIn(-9, 9))

        fun toJson(): JSONObject = JSONObject().put("auto", auto).put("exposure_us", exposureUs).put("iso", iso).put("compensation", compensation)

        val label: String get() = if (auto) "auto" + (if (compensation != 0) " %+d".format(compensation) else "") else "$exposureUs µs ISO $iso"

        companion object {
            const val MIN_EXPOSURE_US = 20
            const val MAX_EXPOSURE_US = 33_000
            const val MIN_ISO = 100
            const val MAX_ISO = 1600
            val CAMERAS = listOf("cam0 (left)", "cam1 (right)", "cam2 (color)")

            fun fromJson(o: JSONObject, d: Exposure = Exposure()) = Exposure(
                o.optBoolean("auto", d.auto), o.optInt("exposure_us", d.exposureUs), o.optInt("iso", d.iso), o.optInt("compensation", d.compensation),
            ).clamped()

            fun listToJson(list: List<Exposure>): String = org.json.JSONArray(list.map { it.toJson() }).toString()

            /** Three exposures from [listToJson]'s output, or null if [text] is missing or malformed. */
            fun listFromJson(text: String?): List<Exposure>? {
                val a = runCatching { org.json.JSONArray(text ?: return null) }.getOrNull() ?: return null
                if (a.length() == 0) return null
                return List(3) { i -> a.optJSONObject(i.coerceAtMost(a.length() - 1))?.let { fromJson(it) } ?: Exposure() }
            }

            /** For profile summaries: "auto exposure", or each camera's when they differ. */
            fun summary(list: List<Exposure>, withColor: Boolean): String {
                val used = if (withColor) list else list.take(2)
                return if (used.distinct().size == 1) {
                    used[0].let { if (it.auto) "auto exposure" + (if (it.compensation != 0) " %+d".format(it.compensation) else "") else "manual exposure ${it.exposureUs} µs ISO ${it.iso}" }
                } else {
                    "exposure " + used.mapIndexed { i, e -> "cam$i ${e.label}" }.joinToString(", ")
                }
            }
        }
    }

    class Mono(
        val image: ByteArray,
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val hostNs: Long,
        val sequence: Long,
        val exposureUs: Long,
        val iso: Int,
        val startOfExposureNs: Long,
        val arrivalNs: Long,
    )

    class StereoFrame(val left: Mono, val right: Mono)

    /** A color frame: a JPEG from the camera, or (if [raw]) NV12 at [width]×[height] (Y plane, then interleaved U/V). */
    class Rgb(val data: ByteArray, val raw: Boolean, val width: Int, val height: Int, val timestampNs: Long, val hostNs: Long,
        val sequence: Long, val exposureUs: Long, val iso: Int, val arrivalNs: Long, val startOfExposureNs: Long)

    /** Raw accelerometer (m/s²) and gyroscope (rad/s) with their own device timestamps. */
    class ImuSample(
        val accelNs: Long, val ax: Double, val ay: Double, val az: Double,
        val gyroNs: Long, val gx: Double, val gy: Double, val gz: Double,
        val accelHostNs: Long, val gyroHostNs: Long, val arrivalNs: Long,
    )

    class Stats(val pairs: Long, val monoFrames: Long, val rgbFrames: Long, val imuSamples: Long, val droppedSequences: Long)

    private val usb = context.getSystemService(UsbManager::class.java)

    @Volatile private var handle = 0L

    @Volatile private var running = false

    /** Whether the camera should be streaming: false cancels a boot in progress once it ends. */
    @Volatile private var wanted = false
    private var threads = emptyList<Thread>()

    /** Boots and shutdowns, in order, off the caller's thread (a boot takes seconds). */
    private val lifecycle = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "OakLifecycle") }

    @Volatile var info: JSONObject? = null
        private set

    private val pairs = AtomicLong()
    private val monoFrames = AtomicLong()
    private val rgbFrames = AtomicLong()
    private val imuSamples = AtomicLong()
    private val sequenceGaps = AtomicLong()

    val isRunning: Boolean get() = running

    /** Any OAK (Movidius vendor id) on USB, booted or not. */
    fun find(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID }

    fun takeStats(): Stats = Stats(pairs.getAndSet(0), monoFrames.getAndSet(0), rgbFrames.getAndSet(0), imuSamples.getAndSet(0), sequenceGaps.get())

    /**
     * Asks for access to the camera if needed, then boots it with [config] in the background.
     * The camera re-enumerates once booted; access is requested again for that device.
     */
    fun start(config: Config) {
        if (wanted) return
        OakNative.unavailableReason?.let {
            listener.onError(it)
            return
        }
        val device = find() ?: run {
            listener.onError("No OAK camera found over USB. Connect it (a USB3 port and cable are best).")
            return
        }
        wanted = true
        watchAttach(true)
        if (usb.hasPermission(device)) boot(config) else requestPermission(device) { granted ->
            if (granted) boot(config) else {
                wanted = false
                watchAttach(false)
                listener.onError("USB access to the OAK camera was denied")
            }
        }
    }

    private fun boot(config: Config) {
        lifecycle.execute {
            if (!wanted) return@execute
            listener.onStatus("Booting the OAK camera (uploading its firmware)…")
            try {
                val e = List(3) { (config.exposures.getOrNull(it) ?: Exposure()).clamped() }
                val plan = config.fpsPlan
                val h = OakNative.start(intArrayOf(
                    config.monoHeight, plan.monoSensorFps, if (config.rgbHeight > 0) 1 else 0, config.rgbHeight,
                    config.jpegQuality, config.imuRateHz, if (e[0].auto) 1 else 0, e[0].exposureUs, e[0].iso, e[0].compensation,
                    if (config.usb3) 1 else 0, config.linkFrames, config.imuPackets,
                    plan.rgbSensorFps, plan.monoEvery, plan.rgbEvery, accelRateFor(config.imuRateHz),
                    if (config.rgbRaw) 1 else 0,
                    if (e[1].auto) 1 else 0, e[1].exposureUs, e[1].iso, e[1].compensation,
                    if (e[2].auto) 1 else 0, e[2].exposureUs, e[2].iso, e[2].compensation,
                ))
                if (!wanted) {
                    OakNative.stop(h)
                    return@execute
                }
                val json = runCatching { JSONObject(OakNative.info(h)) }.getOrElse { JSONObject() }
                info = json
                monoStep = plan.monoEvery.toLong()
                rgbRaw = config.rgbRaw
                handle = h
                running = true
                threads = listOfNotNull(
                    Thread({ pollMono(0) }, "OakLeft"),
                    Thread({ pollMono(1) }, "OakRight"),
                    if (config.rgbHeight > 0) Thread(::pollRgb, "OakRgb") else null,
                    if (config.imuRateHz > 0) Thread(::pollImu, "OakImu") else null,
                ).onEach {
                    it.priority = Thread.MAX_PRIORITY - 1
                    it.start()
                }
                listener.onStarted(json)
            } catch (e: Exception) {
                Log.w(TAG, "Starting the OAK failed", e)
                wanted = false
                listener.onError("OAK camera failed to start: ${e.message}")
            } finally {
                watchAttach(false)
            }
        }
    }

    /** Changes the exposure of [camera] (0 left, 1 right, 2 color) while streaming. */
    fun setExposure(camera: Int, exposure: Exposure) {
        val h = handle
        val e = exposure.clamped()
        if (h != 0L && running) OakNative.setExposure(h, camera, e.auto, e.exposureUs, e.iso, e.compensation)
    }

    /**
     * Stops streaming and closes the camera (it returns to its bootloader) in the background;
     * a boot in progress finishes first and is then closed. Doesn't block.
     */
    fun stop() {
        wanted = false
        running = false
        lifecycle.execute {
            threads.forEach { it.join(1000) }
            threads = emptyList()
            val h = handle
            handle = 0L
            if (h != 0L) OakNative.stop(h)
            info = null
            pendingLeft = null
            pendingRight = null
        }
    }

    // region Polling

    /** Sequence numbers between consecutive kept mono frames (see [FpsPlan.monoEvery]). */
    @Volatile private var monoStep = 1L

    @Volatile private var rgbRaw = false

    @Volatile private var pendingLeft: Mono? = null

    @Volatile private var pendingRight: Mono? = null
    private val pairLock = Any()

    private fun pollMono(which: Int) {
        val meta = LongArray(8)
        var buffer = ByteArray(1280 * 800)
        var lastSequence = -1L
        while (running) {
            val n = OakNative.pollMono(handle, which, buffer, meta, 200)
            if (n < 0) {
                if (running) listener.onError("OAK camera stream stopped")
                break
            }
            if (n == 0) continue
            val image = buffer.copyOf(n)
            val mono = Mono(
                image, meta[3].toInt(), meta[4].toInt(), meta[0], meta[1], meta[2], meta[5], meta[6].toInt(), meta[7],
                SystemClock.elapsedRealtimeNanos(),
            )
            val step = monoStep
            if (lastSequence >= 0 && mono.sequence > lastSequence + step) sequenceGaps.addAndGet((mono.sequence - lastSequence) / step - 1)
            lastSequence = mono.sequence
            monoFrames.incrementAndGet()
            pair(which, mono)
        }
    }

    /** Matches left and right frames by sequence number (the cameras are synchronized). */
    private fun pair(which: Int, mono: Mono) {
        val frame = synchronized(pairLock) {
            if (which == 0) pendingLeft = mono else pendingRight = mono
            val l = pendingLeft ?: return
            val r = pendingRight ?: return
            if (l.sequence != r.sequence) return
            pendingLeft = null
            pendingRight = null
            StereoFrame(l, r)
        }
        pairs.incrementAndGet()
        listener.onStereoFrame(frame)
    }

    private fun pollRgb() {
        val meta = LongArray(8)
        var buffer = ByteArray(1 shl 20)
        while (running) {
            val n = OakNative.pollRgb(handle, buffer, meta, 200)
            if (n < -1) {
                buffer = ByteArray(-n - 1 + (1 shl 16)) // grow and wait for the next frame
                continue
            }
            if (n < 0) break
            if (n == 0) continue
            rgbFrames.incrementAndGet()
            listener.onRgb(Rgb(buffer.copyOf(n), rgbRaw, meta[3].toInt(), meta[4].toInt(), meta[0], meta[1], meta[2], meta[5], meta[6].toInt(),
                SystemClock.elapsedRealtimeNanos(), meta[7]))
        }
    }

    private fun pollImu() {
        val out = DoubleArray(10 * 64)
        while (running) {
            val n = OakNative.pollImu(handle, out, 200)
            if (n < 0) break
            if (n == 0) continue
            val arrival = SystemClock.elapsedRealtimeNanos()
            val samples = List(n) { i ->
                val o = 10 * i
                ImuSample(
                    out[o].toLong(), out[o + 1], out[o + 2], out[o + 3],
                    out[o + 4].toLong(), out[o + 5], out[o + 6], out[o + 7],
                    out[o + 8].toLong(), out[o + 9].toLong(), arrival,
                )
            }
            imuSamples.addAndGet(n.toLong())
            listener.onImu(samples)
        }
    }

    // endregion

    // region USB access

    private var attachReceiver: BroadcastReceiver? = null

    /** While booting, asks for access to the camera again when it re-appears as a booted device. */
    private fun watchAttach(on: Boolean) {
        synchronized(this) {
            attachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            attachReceiver = null
            if (!on) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val d = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                    if (d.vendorId != VENDOR_ID || usb.hasPermission(d)) return
                    listener.onStatus("OAK camera booted: allow USB access again to connect")
                    requestPermission(d) {}
                }
            }
            context.registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), Context.RECEIVER_EXPORTED)
            attachReceiver = receiver
        }
    }

    private fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val action = "$ACTION_PERMISSION.${device.deviceId}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || usb.hasPermission(device))
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val intent = Intent(action).setPackage(context.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, PendingIntent.FLAG_MUTABLE))
    }

    // endregion

    companion object {
        private const val TAG = "OakDevice"

        /** Intel Movidius: OAK cameras before (0x2485) and after (0xf63b) booting. */
        const val VENDOR_ID = 0x03E7

        /** Least [Config.linkFrames] and [Config.imuPackets] (what depthai queues held before). */
        const val MIN_LINK_FRAMES = 8
        const val MIN_IMU_PACKETS = 50

        /**
         * The fastest gyroscope rate that stays regular: the BNO086 (most OAK-D Pro models)
         * offers 400 Hz (higher jitters); the BMI270 rounds requests down to what it supports.
         */
        const val MAX_GYRO_HZ = 400

        /** The BNO086's fastest raw accelerometer rate (it rounds 500 to its 500 Hz step). */
        const val MAX_ACCEL_HZ = 500

        /** Accelerometer rate for a gyroscope rate: its maximum when the gyroscope's is, else the same. */
        fun accelRateFor(gyroHz: Int): Int = when {
            gyroHz <= 0 -> 0
            gyroHz >= MAX_GYRO_HZ -> MAX_ACCEL_HZ
            else -> gyroHz
        }
        private const val ACTION_PERMISSION = "com.xreal.stereoview.OAK_USB_PERMISSION"
    }
}
