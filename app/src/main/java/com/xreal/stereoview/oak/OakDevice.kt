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

/**
 * A Luxonis OAK camera (e.g. OAK-D Pro W) over USB through depthai-core: the left and right
 * mono cameras (raw 8-bit), optionally the color camera as JPEG (encoded on the camera), and
 * its IMU. Timestamps ([Mono.timestampNs] etc.) are on the camera's own clock, shared by
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
        val exposure: Exposure,
        /** Frames per camera that may wait on the phone's side of the USB link (native memory). */
        val linkFrames: Int = MIN_LINK_FRAMES,
        /** IMU packets (one sample or more each) that may wait there. */
        val imuPackets: Int = MIN_IMU_PACKETS,
        /** Color frame rate, or 0 for [fps]. */
        val rgbFps: Int = 0,
    ) {
        val fpsPlan: FpsPlan get() = FpsPlan.of(fps, if (rgbHeight > 0 && rgbFps > 0) rgbFps else fps)
    }

    /**
     * How the mono and color frame rates are produced. The cameras' capture times are kept
     * aligned by the OAK's firmware only while all sensors run at the same rate, so when one
     * rate is a multiple of the other, every sensor runs at the faster rate ([sensorFps]) and
     * the slower stream keeps every [monoEvery]th / [rgbEvery]th frame, on the camera (by
     * sequence number, so left and right stay paired): the kept frames remain synchronized.
     * Otherwise ([synchronized] false) the color sensor runs freely at its own rate.
     */
    data class FpsPlan(
        val monoFps: Int, val rgbFps: Int, val monoSensorFps: Int, val rgbSensorFps: Int,
        val monoEvery: Int, val rgbEvery: Int, val synchronized: Boolean,
    ) {
        companion object {
            fun of(mono: Int, rgb: Int): FpsPlan {
                val hi = maxOf(mono, rgb)
                val lo = minOf(mono, rgb).coerceAtLeast(1)
                return if (hi % lo == 0) {
                    FpsPlan(mono, rgb, hi, hi, hi / mono.coerceAtLeast(1), hi / rgb.coerceAtLeast(1), true)
                } else {
                    FpsPlan(mono, rgb, mono, rgb, 1, 1, false)
                }
            }
        }
    }

    data class Exposure(val auto: Boolean, val exposureUs: Int, val iso: Int, val compensation: Int)

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

    class Rgb(val jpeg: ByteArray, val width: Int, val height: Int, val timestampNs: Long, val hostNs: Long,
              val sequence: Long, val exposureUs: Long, val iso: Int, val arrivalNs: Long)

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
                val e = config.exposure
                val plan = config.fpsPlan
                val h = OakNative.start(intArrayOf(
                    config.monoHeight, plan.monoSensorFps, if (config.rgbHeight > 0) 1 else 0, config.rgbHeight,
                    config.jpegQuality, config.imuRateHz, if (e.auto) 1 else 0, e.exposureUs, e.iso, e.compensation,
                    if (config.usb3) 1 else 0, config.linkFrames, config.imuPackets,
                    plan.rgbSensorFps, plan.monoEvery, plan.rgbEvery, accelRateFor(config.imuRateHz),
                ))
                if (!wanted) {
                    OakNative.stop(h)
                    return@execute
                }
                val json = runCatching { JSONObject(OakNative.info(h)) }.getOrElse { JSONObject() }
                info = json
                monoStep = plan.monoEvery.toLong()
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

    fun setExposure(e: Exposure) {
        val h = handle
        if (h != 0L && running) OakNative.setExposure(h, e.auto, e.exposureUs, e.iso, e.compensation)
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
            listener.onRgb(Rgb(buffer.copyOf(n), meta[3].toInt(), meta[4].toInt(), meta[0], meta[1], meta[2], meta[5], meta[6].toInt(),
                SystemClock.elapsedRealtimeNanos()))
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
