package com.xreal.stereoview.glasses

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.xreal.stereoview.util.Uvc
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams the Air 2 Ultra's tracking cameras (and optionally its IMU) straight over USB.
 *
 * The camera's USB video interfaces, and with [start]'s `withImu` the IMU's HID interface,
 * are claimed with force: that detaches them from the system (kernel driver / XREAL
 * service) until the glasses are replugged, so XREAL's own camera use and, with the IMU,
 * its head tracking stop working meanwhile.
 *
 * One thread keeps several USB reads queued on both endpoints; finished camera frames are
 * descrambled and paired on a second thread. Listener calls come from those threads.
 */
class GlassesDevice(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(message: String)
        fun onError(message: String)
        /** A left/right pair with the same sequence number; images are 480×640, upright. */
        fun onStereoFrame(frame: StereoFrame)
        fun onImu(sample: GlassesProtocol.ImuSample, arrivalNs: Long)
        /** The glasses' factory calibration (JSON), read before the IMU stream starts. */
        fun onCalibration(json: String)
    }

    /** One camera image; [timestampNs] is on the glasses' clock, [arrivalNs] on CLOCK_BOOTTIME. */
    class Eye(val image: ByteArray, val timestampNs: Long, val sequence: Long, val arrivalNs: Long)

    class StereoFrame(val left: Eye, val right: Eye)

    /** Counters since the previous [takeStats]. */
    data class Stats(val frames: Int, val pairs: Int, val incomplete: Int, val dropped: Int, val imuSamples: Int, val megabytes: Double)

    private val manager = context.getSystemService(UsbManager::class.java)
    @Volatile
    private var running = false
    private var ioThread: Thread? = null
    private var decodeThread: HandlerThread? = null

    private val frames = AtomicInteger()
    private val pairs = AtomicInteger()
    private val incomplete = AtomicInteger()
    private val dropped = AtomicInteger()
    private val imuSamples = AtomicInteger()
    private val bytes = java.util.concurrent.atomic.AtomicLong()

    /** The first attached Air 2 Ultra, if any. */
    fun find(): UsbDevice? = manager?.deviceList?.values?.firstOrNull {
        it.vendorId == GlassesProtocol.VENDOR_ID && it.productId == GlassesProtocol.AIR_2_ULTRA
    }

    fun hasPermission(device: UsbDevice) = manager?.hasPermission(device) == true

    /** Asks the user for access to [device] (system dialog); [onResult] runs on the main thread. */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = manager ?: return onResult(false)
        if (manager.hasPermission(device)) return onResult(true)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent) {
                context.unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(ACTION_PERMISSION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val intent = android.content.Intent(ACTION_PERMISSION).setPackage(context.packageName)
        manager.requestPermission(device, android.app.PendingIntent.getBroadcast(context, 1, intent, android.app.PendingIntent.FLAG_MUTABLE))
    }

    val isRunning: Boolean get() = running

    /** Starts streaming; [device] must already have USB permission. */
    fun start(device: UsbDevice, withImu: Boolean) {
        if (running) return
        running = true
        val decode = HandlerThread("GlassesDecode", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        decodeThread = decode
        ioThread = Thread({ run(device, withImu, Handler(decode.looper)) }, "GlassesUsb").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        ioThread?.join(2000)
        ioThread = null
        decodeThread?.quitSafely()
        decodeThread = null
    }

    fun takeStats() = Stats(
        frames.getAndSet(0), pairs.getAndSet(0), incomplete.getAndSet(0), dropped.getAndSet(0),
        imuSamples.getAndSet(0), bytes.getAndSet(0) / 1e6,
    )

    private class Tagged(val endpoint: UsbEndpoint, val buffer: ByteBuffer)

    private fun run(device: UsbDevice, withImu: Boolean, decoder: Handler) {
        val connection = manager?.openDevice(device)
        if (connection == null) {
            fail("Cannot open the glasses (USB permission?)")
            return
        }
        val claimed = mutableListOf<UsbInterface>()
        val requests = mutableListOf<UsbRequest>()
        var streaming: UsbInterface? = null
        try {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface).filter { it.alternateSetting == 0 }
            val video = interfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO }
            streaming = video.firstOrNull { it.interfaceSubclass == 2 } ?: return fail("No camera interface on the glasses")
            val cameraIn = streaming.endpoints().firstOrNull { it.isIn() && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: return fail("No camera endpoint")
            for (iface in video) {
                if (!connection.claimInterface(iface, true)) return fail("Cannot take over the glasses camera (interface ${iface.id})")
                claimed += iface
            }

            var imu: Imu? = null
            if (withImu) {
                val hid = listOf(GlassesProtocol.IMU_INTERFACE, GlassesProtocol.IMU_INTERFACE_FALLBACK)
                    .mapNotNull { id -> interfaces.firstOrNull { it.id == id && it.interfaceClass == UsbConstants.USB_CLASS_HID } }
                    .firstOrNull { connection.claimInterface(it, true) }
                if (hid == null) {
                    listener.onError("Cannot take over the glasses IMU; recording without it")
                } else {
                    claimed += hid
                    val inEp = hid.endpoints().first { it.isIn() }
                    val outEp = hid.endpoints().first { !it.isIn() }
                    imu = Imu(connection, outEp)
                    repeat(IMU_REQUESTS) { requests += queue(connection, inEp, GlassesProtocol.SENSOR_REPORT_SIZE) }
                    imu.begin()
                }
            }

            val formats = Uvc.parseFormats(connection.rawDescriptors ?: ByteArray(0))
            val negotiation = Uvc.negotiate(connection, streaming, formats)
            if (!negotiation.ok) return fail("Camera did not start: ${negotiation.log}")
            listener.onStatus("Glasses cameras: %.0f fps%s".format(negotiation.fps, if (imu != null) ", IMU on" else ""))
            repeat(CAMERA_REQUESTS) { requests += queue(connection, cameraIn, negotiation.payloadBytes) }

            val assembler = Assembler(decoder)
            while (running) {
                val done = try {
                    connection.requestWait(500)
                } catch (e: TimeoutException) {
                    imu?.tick()
                    continue
                } ?: continue
                val tagged = done.clientData as Tagged
                val buffer = tagged.buffer
                val length = buffer.position()
                if (tagged.endpoint == cameraIn) {
                    bytes.addAndGet(length.toLong())
                    assembler.payload(buffer.array(), length)
                } else {
                    imu?.report(buffer.array(), length)
                }
                buffer.clear()
                if (running && !done.queue(buffer)) Log.w(TAG, "Re-queueing a USB request failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Glasses streaming failed", e)
            listener.onError("Glasses streaming failed: ${e.message}")
        } finally {
            requests.forEach { it.cancel() }
            requests.forEach { it.close() }
            streaming?.let { connection.setInterface(it) } // alternate setting 0 stops the stream
            claimed.forEach { connection.releaseInterface(it) }
            connection.close()
            running = false
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        listener.onError(message)
    }

    private fun queue(connection: UsbDeviceConnection, endpoint: UsbEndpoint, size: Int): UsbRequest {
        val buffer = ByteBuffer.allocate(size)
        return UsbRequest().apply {
            initialize(connection, endpoint)
            clientData = Tagged(endpoint, buffer)
            check(queue(buffer)) { "Cannot queue a USB read" }
        }
    }

    /**
     * Rebuilds frames from UVC payloads (a 2-byte header, then data) and hands complete ones
     * to the decode thread through a small pool of buffers, dropping frames when it lags.
     */
    private inner class Assembler(private val decoder: Handler) {
        private val pool = ArrayBlockingQueue<ByteArray>(4).apply { repeat(4) { add(ByteArray(GlassesProtocol.FRAME_BYTES)) } }
        private var current: ByteArray? = null
        private var filled = 0
        private var lastFid = -1
        private val pairing = Pairing()

        fun payload(data: ByteArray, length: Int) {
            if (length < 2) return
            val header = data[0].toInt() and 0xff
            if (header < 2 || header > length) return
            val info = data[1].toInt() and 0xff
            val fid = info and 0x01
            if (lastFid >= 0 && fid != lastFid) finish() // new frame without an EOF on the last
            lastFid = fid
            val target = current ?: pool.poll()?.also { current = it } ?: run {
                dropped.incrementAndGet()
                return
            }
            val n = minOf(length - header, target.size - filled)
            System.arraycopy(data, header, target, filled, n)
            filled += n
            if (info and 0x02 != 0) finish()
        }

        private fun finish() {
            val frame = current ?: return
            if (filled != frame.size) {
                incomplete.incrementAndGet()
                filled = 0
                return
            }
            val arrival = SystemClock.elapsedRealtimeNanos()
            current = null
            filled = 0
            decoder.post {
                val info = GlassesProtocol.frameInfo(frame)
                val upright = GlassesProtocol.rotate(GlassesProtocol.descramble(frame), info.isRight)
                pool.offer(frame)
                frames.incrementAndGet()
                pairing.add(Eye(upright, info.timestampNs, info.sequence, arrival), info.isRight)
            }
        }
    }

    /** Matches left and right images by sequence number (decode thread). */
    private inner class Pairing {
        private var left: Eye? = null
        private var right: Eye? = null

        fun add(eye: Eye, isRight: Boolean) {
            if (isRight) right = eye else left = eye
            val l = left ?: return
            val r = right ?: return
            if (l.sequence == r.sequence) {
                left = null
                right = null
                pairs.incrementAndGet()
                listener.onStereoFrame(StereoFrame(l, r))
            }
        }
    }

    /**
     * The IMU handshake from Monado's driver: query the stream state, the static id and the
     * calibration (in segments), then start the IMU stream; sensor reports follow.
     */
    private inner class Imu(private val connection: UsbDeviceConnection, private val out: UsbEndpoint) {
        private var calibration: ByteArray? = null
        private var calibrationPos = 0
        private var streaming = false
        private var lastCommandMs = 0L
        private var fallbackSent = false

        fun begin() = send(GlassesProtocol.MSG_START_IMU_DATA, byteArrayOf(0xAA.toByte()))

        /** Called when nothing arrived for a while: retry, then start without calibration. */
        fun tick() {
            if (streaming || fallbackSent || SystemClock.elapsedRealtime() - lastCommandMs < 1000) return
            fallbackSent = true
            listener.onStatus("Glasses IMU: no calibration reply, starting the stream anyway")
            send(GlassesProtocol.MSG_START_IMU_DATA, byteArrayOf(1))
        }

        fun report(data: ByteArray, length: Int) {
            if (data[0] == 0xAA.toByte()) {
                GlassesProtocol.parseControlReply(data, length)?.let(::reply)
                return
            }
            val sample = GlassesProtocol.parseImu(data, length) ?: return
            streaming = true
            imuSamples.incrementAndGet()
            listener.onImu(sample, SystemClock.elapsedRealtimeNanos())
        }

        private fun reply(r: GlassesProtocol.ControlReply) {
            when (r.msgId) {
                GlassesProtocol.MSG_START_IMU_DATA -> if (calibration == null && !streaming) send(GlassesProtocol.MSG_GET_STATIC_ID)
                GlassesProtocol.MSG_GET_STATIC_ID -> send(GlassesProtocol.MSG_GET_CAL_DATA_LENGTH)
                GlassesProtocol.MSG_GET_CAL_DATA_LENGTH -> {
                    val length = GlassesProtocol.le32(r.data, 0).toInt()
                    if (length in 1..1_000_000) {
                        calibration = ByteArray(length)
                        calibrationPos = 0
                        send(GlassesProtocol.MSG_CAL_DATA_GET_NEXT_SEGMENT)
                    } else {
                        send(GlassesProtocol.MSG_START_IMU_DATA, byteArrayOf(1))
                    }
                }
                GlassesProtocol.MSG_CAL_DATA_GET_NEXT_SEGMENT -> {
                    val buffer = calibration ?: return
                    val n = minOf(buffer.size - calibrationPos, GlassesProtocol.SENSOR_REPORT_SIZE - 8, r.data.size)
                    System.arraycopy(r.data, 0, buffer, calibrationPos, n)
                    calibrationPos += n
                    if (calibrationPos >= buffer.size) {
                        listener.onCalibration(String(buffer, Charsets.UTF_8).trimEnd('\u0000'))
                        send(GlassesProtocol.MSG_START_IMU_DATA, byteArrayOf(1))
                    } else {
                        send(GlassesProtocol.MSG_CAL_DATA_GET_NEXT_SEGMENT)
                    }
                }
            }
        }

        private fun send(msgId: Int, data: ByteArray = ByteArray(0)) {
            lastCommandMs = SystemClock.elapsedRealtime()
            val command = GlassesProtocol.sensorCommand(msgId, data)
            if (connection.bulkTransfer(out, command, command.size, 200) < 0) Log.w(TAG, "IMU command 0x%x failed".format(msgId))
        }
    }

    private fun UsbInterface.endpoints() = (0 until endpointCount).map(::getEndpoint)
    private fun UsbEndpoint.isIn() = direction == UsbConstants.USB_DIR_IN

    private companion object {
        const val TAG = "GlassesDevice"
        const val CAMERA_REQUESTS = 12
        const val IMU_REQUESTS = 4
        const val ACTION_PERMISSION = "com.xreal.stereoview.GLASSES_USB_PERMISSION"
    }
}
