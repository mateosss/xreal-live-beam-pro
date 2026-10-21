package com.xreal.stereoview.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal USB Video Class support for the glasses' camera: decoding the video formats and
 * camera controls from the configuration descriptors, the probe/commit negotiation, reading
 * and setting controls (exposure, gain), and grabbing frames for the USB probe.
 */
object Uvc {
    /** One frame size of a format, with its frame intervals (100 ns units). */
    data class Frame(val index: Int, val width: Int, val height: Int, val maxFrameBytes: Int, val defaultInterval: Int, val intervals: List<Int>) {
        val fps: List<Double> get() = intervals.filter { it > 0 }.map { 1e7 / it }
    }

    /** A format of streaming interface [interfaceNumber]; [fourcc] is decoded from the GUID when it is one. */
    data class Format(
        val interfaceNumber: Int,
        val index: Int,
        val kind: String,
        val fourcc: String?,
        val guid: String?,
        val bitsPerPixel: Int?,
        val frames: MutableList<Frame> = mutableListOf(),
    ) {
        fun describe(): String = buildString {
            append("format $index on interface $interfaceNumber: $kind")
            fourcc?.let { append(" '$it'") }
            bitsPerPixel?.let { append(", $it bits/pixel") }
            if (fourcc == null && guid != null) append(", GUID $guid")
            for (f in frames) {
                append("\n  frame ${f.index}: ${f.width}×${f.height}, up to ${f.maxFrameBytes} bytes, ")
                append(f.fps.joinToString(prefix = "fps [", postfix = "]") { "%.1f".format(it) })
                append(", default %.1f fps".format(if (f.defaultInterval > 0) 1e7 / f.defaultInterval else 0.0))
            }
        }
    }

    /** Parses the VideoStreaming format and frame descriptors out of raw configuration descriptors. */
    fun parseFormats(raw: ByteArray): List<Format> {
        val formats = mutableListOf<Format>()
        var iface = -1
        var subclass = -1
        var i = 0
        while (i + 2 <= raw.size) {
            val length = raw[i].u8()
            if (length < 2 || i + length > raw.size) break
            val type = raw[i + 1].u8()
            if (type == DESC_INTERFACE && length >= 9) {
                iface = raw[i + 2].u8()
                subclass = if (raw[i + 5].u8() == UsbConstants.USB_CLASS_VIDEO) raw[i + 6].u8() else -1
            } else if (type == DESC_CS_INTERFACE && subclass == SUBCLASS_STREAMING && length >= 3) {
                val sub = raw[i + 2].u8()
                when (sub) {
                    VS_FORMAT_UNCOMPRESSED, VS_FORMAT_FRAME_BASED -> if (length >= 22) {
                        val guid = raw.copyOfRange(i + 5, i + 21)
                        formats += Format(
                            iface, raw[i + 3].u8(),
                            if (sub == VS_FORMAT_UNCOMPRESSED) "uncompressed" else "frame-based",
                            fourcc(guid), guid.joinToString("") { "%02x".format(it) }, raw[i + 21].u8(),
                        )
                    }
                    VS_FORMAT_MJPEG -> formats += Format(iface, raw[i + 3].u8(), "MJPEG", "MJPG", null, null)
                    VS_FRAME_UNCOMPRESSED, VS_FRAME_MJPEG, VS_FRAME_FRAME_BASED -> if (length >= 26) {
                        val frameBased = sub == VS_FRAME_FRAME_BASED
                        val count = raw[i + 25].u8()
                        val first = i + if (frameBased) 30 else 26
                        // Discrete intervals, or (count 0) a continuous min/max/step: keep min and max.
                        val intervals = (0 until if (count == 0) 2 else count)
                            .map { first + 4 * it }
                            .filter { it + 4 <= i + length }
                            .map { raw.u32(it) }
                        formats.lastOrNull()?.frames?.add(Frame(
                            raw[i + 3].u8(), raw.u16(i + 5), raw.u16(i + 7),
                            if (frameBased) 0 else raw.u32(i + 17), raw.u32(i + 21), intervals,
                        ))
                    }
                }
            }
            i += length
        }
        return formats
    }

    /**
     * Negotiates the first format and frame of [streaming] with a probe/commit exchange, reads
     * bulk payloads from [endpoint] until two whole frames arrived, stops the stream and writes
     * the second frame into [dir]. Returns a report.
     */
    fun grabFrame(
        connection: UsbDeviceConnection,
        streaming: UsbInterface,
        endpoint: UsbEndpoint,
        formats: List<Format>,
        dir: File,
    ): String = buildString {
        val n = negotiate(connection, streaming, formats)
        appendLine(n.log)
        if (!n.ok) return@buildString
        val size = n.frame
        val chosen = n.format
        val frameBytes = n.frameBytes
        val payload = n.payloadBytes

        dir.mkdirs()
        val base = "glasses_stream_${size?.width ?: 0}x${size?.height ?: 0}_${chosen?.fourcc ?: "unknown"}"
        val rawFile = File(dir, "$base.raw")
        val stats = streamToFile(connection, endpoint, payload, rawFile, File(dir, "$base.reads.csv"))
        connection.setInterface(streaming) // alternate setting 0 stops the stream
        append(stats.report(frameBytes))
        appendLine("raw stream saved to $rawFile (+ .reads.csv with every read's size and first bytes)")

        // Keep the largest assembled frame, and also show it as grayscale guesses.
        val picked = stats.frames.maxByOrNull { it.size } ?: return@buildString
        val name = "glasses_frame_${size?.width ?: 0}x${size?.height ?: 0}_${chosen?.fourcc ?: "unknown"}"
        File(dir, "$name.bin").writeBytes(picked)
        appendLine("largest frame: ${picked.size} bytes (expected $frameBytes), saved ${File(dir, "$name.bin")}")
        // A "YUY2" stream may really be packed grayscale (e.g. tracking cameras), so try both
        // plausible layouts; only whole frames are written.
        if (size != null) {
            val layouts = when (picked.size) {
                size.width * size.height -> listOf(size.width to size.height)
                size.width * size.height * 2 -> listOf(size.width * 2 to size.height, size.width to size.height * 2)
                else -> emptyList()
            }
            for ((w, h) in layouts) {
                val pgm = File(dir, "${name}_as_gray_${w}x$h.pgm")
                pgm.outputStream().use {
                    it.write("P5\n$w $h\n255\n".toByteArray())
                    it.write(picked)
                }
                appendLine("saved $pgm")
            }
        }
    }

    /**
     * A camera setting the VideoControl interface advertises: [selector] of unit [unitId]
     * (camera terminal or processing unit), [size] bytes, signed or not.
     */
    data class Control(val name: String, val unitId: Int, val selector: Int, val size: Int, val signed: Boolean)

    /** The controls of a VideoControl interface, plus a description of every unit (for reports). */
    class Controls(val interfaceNumber: Int, val available: List<Control>, val units: List<String>) {
        fun find(name: String) = available.firstOrNull { it.name == name }
    }

    /** Range and current value of a [Control], as GET_MIN/MAX/RES/DEF/CUR report them. */
    data class ControlRange(val min: Int, val max: Int, val step: Int, val default: Int, val current: Int)

    /**
     * Parses the VideoControl interface's camera terminal and processing unit descriptors into
     * the exposure, gain and brightness controls they support.
     */
    fun parseControls(raw: ByteArray): Controls? {
        var iface = -1
        var subclass = -1
        var vcInterface = -1
        val controls = mutableListOf<Control>()
        val units = mutableListOf<String>()
        var i = 0
        while (i + 2 <= raw.size) {
            val length = raw[i].u8()
            if (length < 2 || i + length > raw.size) break
            val type = raw[i + 1].u8()
            if (type == DESC_INTERFACE && length >= 9) {
                iface = raw[i + 2].u8()
                subclass = if (raw[i + 5].u8() == UsbConstants.USB_CLASS_VIDEO) raw[i + 6].u8() else -1
            } else if (type == DESC_CS_INTERFACE && subclass == SUBCLASS_CONTROL && length >= 3) {
                vcInterface = iface
                val id = raw[i + 3].u8()
                when (raw[i + 2].u8()) {
                    VC_INPUT_TERMINAL -> if (length >= 8) {
                        val terminalType = raw.u16(i + 4)
                        if (terminalType == ITT_CAMERA && length >= 15) {
                            val bits = raw.bits(i + 15, raw[i + 14].u8())
                            units += "camera terminal $id, controls 0x%x: %s".format(bits, CT_NAMES.filterKeys { bits.bit(it) }.values.joinToString())
                            if (bits.bit(1)) controls += Control(AE_MODE, id, CT_AE_MODE, 1, false)
                            if (bits.bit(3)) controls += Control(EXPOSURE, id, CT_EXPOSURE_ABSOLUTE, 4, false)
                        } else {
                            units += "input terminal $id, type 0x%04x".format(terminalType)
                        }
                    }
                    VC_PROCESSING_UNIT -> if (length >= 8) {
                        val bits = raw.bits(i + 8, raw[i + 7].u8())
                        units += "processing unit $id, controls 0x%x: %s".format(bits, PU_NAMES.filterKeys { bits.bit(it) }.values.joinToString())
                        if (bits.bit(0)) controls += Control(BRIGHTNESS, id, PU_BRIGHTNESS, 2, true)
                        if (bits.bit(9)) controls += Control(GAIN, id, PU_GAIN, 2, false)
                    }
                    VC_EXTENSION_UNIT -> if (length >= 21) {
                        val guid = raw.copyOfRange(i + 4, i + 20).joinToString("") { "%02x".format(it) }
                        units += "extension unit $id, GUID $guid, ${raw[i + 20].u8()} controls"
                    }
                    VC_OUTPUT_TERMINAL -> units += "output terminal $id"
                }
            }
            i += length
        }
        return if (vcInterface >= 0) Controls(vcInterface, controls, units) else null
    }

    /** Reads [control]'s range and value, or null if the camera rejects the requests. */
    fun range(connection: UsbDeviceConnection, controls: Controls, control: Control): ControlRange? {
        fun get(request: Int): Int? {
            val data = ByteArray(control.size)
            val n = connection.controlTransfer(
                GET_REQUEST, request, control.selector shl 8, (control.unitId shl 8) or controls.interfaceNumber, data, data.size, 500,
            )
            return if (n == control.size) data.decode(control) else null
        }
        val cur = get(GET_CUR) ?: return null
        if (control.name == AE_MODE) return ControlRange(1, 8, get(GET_RES) ?: 0, get(GET_DEF) ?: cur, cur)
        return ControlRange(get(GET_MIN) ?: cur, get(GET_MAX) ?: cur, (get(GET_RES) ?: 1).coerceAtLeast(1), get(GET_DEF) ?: cur, cur)
    }

    /** Sets [control] to [value]; returns whether the camera accepted it. */
    fun set(connection: UsbDeviceConnection, controls: Controls, control: Control, value: Int): Boolean {
        val data = ByteArray(control.size) { (value ushr (8 * it)).toByte() }
        return connection.controlTransfer(
            SET_REQUEST, SET_CUR, control.selector shl 8, (control.unitId shl 8) or controls.interfaceNumber, data, data.size, 500,
        ) >= 0
    }

    private fun ByteArray.decode(control: Control): Int {
        var v = 0
        for (k in 0 until control.size) v = v or (this[k].u8() shl (8 * k))
        if (control.signed && control.size < 4) {
            val shift = 32 - 8 * control.size
            v = (v shl shift) shr shift
        }
        return v
    }

    private fun Long.bit(i: Int) = (this shr i) and 1L == 1L

    private fun ByteArray.bits(start: Int, count: Int): Long {
        var v = 0L
        for (k in 0 until minOf(count, 8)) if (start + k < size) v = v or (this[start + k].u8().toLong() shl (8 * k))
        return v
    }

    const val AE_MODE = "auto exposure mode"
    const val EXPOSURE = "exposure time"
    const val GAIN = "gain"
    const val BRIGHTNESS = "brightness"

    /** CT_AE_MODE values: a bitmap with one bit set. */
    const val AE_MANUAL = 1
    const val AE_AUTO = 2
    const val AE_SHUTTER_PRIORITY = 4
    const val AE_APERTURE_PRIORITY = 8

    private val CT_NAMES = mapOf(
        0 to "scanning mode", 1 to "auto exposure mode", 2 to "auto exposure priority", 3 to "exposure time (absolute)",
        4 to "exposure time (relative)", 5 to "focus (absolute)", 6 to "focus (relative)", 7 to "iris (absolute)",
        8 to "iris (relative)", 9 to "zoom (absolute)", 10 to "zoom (relative)", 11 to "pan/tilt (absolute)",
        12 to "pan/tilt (relative)", 13 to "roll (absolute)", 14 to "roll (relative)", 17 to "focus auto", 18 to "privacy",
    )
    private val PU_NAMES = mapOf(
        0 to "brightness", 1 to "contrast", 2 to "hue", 3 to "saturation", 4 to "sharpness", 5 to "gamma",
        6 to "white balance temperature", 7 to "white balance component", 8 to "backlight compensation", 9 to "gain",
        10 to "power line frequency", 11 to "hue auto", 12 to "white balance temperature auto",
        13 to "white balance component auto", 14 to "digital multiplier", 15 to "digital multiplier limit",
    )

    /** Result of [negotiate]: the stream parameters the camera committed to. */
    class Negotiation(
        val ok: Boolean,
        val format: Format?,
        val frame: Frame?,
        val frameBytes: Int,
        val payloadBytes: Int,
        val fps: Double,
        val log: String,
    )

    /**
     * Probe/commit exchange on [streaming] for the first format and frame size: reads the
     * camera's defaults, requests that mode, reads back what it settled on and commits it.
     * For a bulk endpoint the stream starts with the commit.
     */
    fun negotiate(connection: UsbDeviceConnection, streaming: UsbInterface, formats: List<Format>): Negotiation {
        val log = StringBuilder()
        fun fail(message: String) = Negotiation(false, null, null, 0, 0, 0.0, log.append(message).toString())
        val index = streaming.id
        val probe = ByteArray(48)
        val length = connection.controlTransfer(GET_REQUEST, GET_CUR, VS_PROBE_CONTROL shl 8, index, probe, probe.size, 1000)
        if (length < 26) return fail("GET_CUR probe failed ($length)")
        log.appendLine("probe control: $length bytes (UVC ${if (length >= 34) "1.1+" else "1.0"})")
        val format = formats.firstOrNull { it.interfaceNumber == index } ?: formats.firstOrNull()
        val frame = format?.frames?.firstOrNull()
        val request = probe.copyOf(length)
        if (format != null && frame != null) {
            request[2] = format.index.toByte()
            request[3] = frame.index.toByte()
            request.putU32(4, frame.defaultInterval)
        }
        if (connection.controlTransfer(SET_REQUEST, SET_CUR, VS_PROBE_CONTROL shl 8, index, request, length, 1000) < 0) {
            return fail("SET_CUR probe failed")
        }
        val negotiated = ByteArray(length)
        connection.controlTransfer(GET_REQUEST, GET_CUR, VS_PROBE_CONTROL shl 8, index, negotiated, length, 1000)
        val chosen = formats.firstOrNull { it.interfaceNumber == index && it.index == negotiated[2].u8() }
        val size = chosen?.frames?.firstOrNull { it.index == negotiated[3].u8() }
        val frameBytes = negotiated.u32(18)
        val payload = negotiated.u32(22).coerceIn(512, 4 * 1024 * 1024)
        val fps = negotiated.u32(4).takeIf { it > 0 }?.let { 1e7 / it } ?: 0.0
        log.append(
            "negotiated: format ${negotiated[2].u8()} frame ${negotiated[3].u8()} (${size?.let { "${it.width}×${it.height}" } ?: "?"}), " +
                "%.1f fps, frame %d bytes, payload %d bytes".format(fps, frameBytes, payload),
        )
        if (connection.controlTransfer(SET_REQUEST, SET_CUR, VS_COMMIT_CONTROL shl 8, index, negotiated, length, 1000) < 0) {
            return fail("\nSET_CUR commit failed")
        }
        return Negotiation(true, chosen, size, frameBytes, payload, fps, log.toString())
    }

    /** What a raw capture contained. */
    class StreamStats {
        var reads = 0
        var timeouts = 0
        var bytes = 0L
        val readSizes = sortedMapOf<Int, Int>()
        val headerLengths = sortedMapOf<Int, Int>()
        var eofFlags = 0
        var errorFlags = 0
        var fidToggles = 0
        var nonZeroBytes = 0L
        val frames = mutableListOf<ByteArray>()
        val frameSizes = mutableListOf<Int>()
        var seconds = 0.0

        fun report(expectedFrame: Int) = buildString {
            appendLine("captured %.2f s: %d reads (%d empty/timeouts), %d bytes (%.1f MB/s), %d non-zero bytes".format(
                seconds, reads, timeouts, bytes, bytes / 1e6 / seconds.coerceAtLeast(1e-3), nonZeroBytes,
            ))
            appendLine("read sizes (bytes×count): ${readSizes.entries.joinToString { "${it.key}×${it.value}" }}")
            appendLine("header lengths (byte 0): ${headerLengths.entries.joinToString { "${it.key}×${it.value}" }}")
            appendLine("EOF flags: $eofFlags, FID toggles: $fidToggles, error flags: $errorFlags")
            appendLine("assembled frames: ${frameSizes.size}, sizes: ${frameSizes.take(30).joinToString()}" +
                if (frameSizes.size > 30) " …" else "")
            val whole = frameSizes.count { it == expectedFrame }
            appendLine("frames of the expected $expectedFrame bytes: $whole" +
                if (whole > 1) " (≈ %.1f fps)".format(whole / seconds) else "")
        }
    }

    /**
     * Reads the bulk stream for [CAPTURE_SECONDS], writing every transfer to [rawFile] and its
     * size and first bytes to [indexFile], and assembles frames from UVC payload headers (a new
     * frame starts when FID toggles or after EOF).
     */
    private fun streamToFile(connection: UsbDeviceConnection, endpoint: UsbEndpoint, payload: Int, rawFile: File, indexFile: File): StreamStats {
        val stats = StreamStats()
        val buffer = ByteArray(payload)
        val current = ByteArrayOutputStream()
        var lastFid = -1
        val start = System.nanoTime()
        rawFile.outputStream().buffered().use { raw ->
            indexFile.bufferedWriter().use { index ->
                index.write("read,time_ms,length,first_bytes\n")
                while (System.nanoTime() - start < CAPTURE_SECONDS * 1_000_000_000L) {
                    val n = connection.bulkTransfer(endpoint, buffer, buffer.size, 200)
                    stats.reads++
                    if (n <= 0) {
                        stats.timeouts++
                        continue
                    }
                    stats.bytes += n
                    stats.readSizes.merge(n, 1, Int::plus)
                    for (k in 0 until n) if (buffer[k].toInt() != 0) stats.nonZeroBytes++
                    raw.write(buffer, 0, n)
                    if (stats.reads <= MAX_INDEX_ROWS) {
                        index.write("${stats.reads},%.3f,$n,${buffer.take(minOf(n, 16)).joinToString(" ") { "%02x".format(it) }}\n"
                            .format((System.nanoTime() - start) / 1e6))
                    }
                    val header = buffer[0].u8()
                    stats.headerLengths.merge(header, 1, Int::plus)
                    if (header < 2 || header > n) {
                        current.write(buffer, 0, n) // no header: raw data
                        continue
                    }
                    val info = buffer[1].u8()
                    val fid = info and 0x01
                    if (lastFid >= 0 && fid != lastFid) {
                        stats.fidToggles++
                        if (current.size() > 0) stats.finishFrame(current)
                    }
                    lastFid = fid
                    if (info and 0x40 != 0) stats.errorFlags++
                    current.write(buffer, header, n - header)
                    if (info and 0x02 != 0) {
                        stats.eofFlags++
                        stats.finishFrame(current)
                    }
                }
            }
        }
        stats.seconds = (System.nanoTime() - start) / 1e9
        return stats
    }

    private fun StreamStats.finishFrame(current: ByteArrayOutputStream) {
        frameSizes += current.size()
        // Keep a few frames in memory, preferring large ones.
        if (frames.size < 4) frames += current.toByteArray()
        else if (current.size() > frames.minOf { it.size }) {
            frames.remove(frames.minByOrNull { it.size })
            frames += current.toByteArray()
        }
        current.reset()
    }

    private const val CAPTURE_SECONDS = 2
    private const val MAX_INDEX_ROWS = 5000

    /** FourCC from a UVC format GUID of the form XXXXXXXX-0000-0010-8000-00AA00389B71. */
    private fun fourcc(guid: ByteArray): String? {
        val suffix = byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71)
        if (!guid.copyOfRange(4, 16).contentEquals(suffix)) return null
        val chars = guid.copyOfRange(0, 4).map { it.u8() }
        return if (chars.all { it in 32..126 }) chars.joinToString("") { it.toChar().toString() } else null
    }

    private fun Byte.u8() = toInt() and 0xff
    private fun ByteArray.u16(i: Int) = this[i].u8() or (this[i + 1].u8() shl 8)
    private fun ByteArray.u32(i: Int) = if (i + 4 <= size) u16(i) or (u16(i + 2) shl 16) else 0
    private fun ByteArray.putU32(i: Int, v: Int) {
        for (k in 0..3) this[i + k] = (v ushr (8 * k)).toByte()
    }

    private const val DESC_INTERFACE = 0x04
    private const val DESC_CS_INTERFACE = 0x24
    private const val SUBCLASS_CONTROL = 0x01
    private const val SUBCLASS_STREAMING = 0x02
    private const val VC_INPUT_TERMINAL = 0x02
    private const val VC_OUTPUT_TERMINAL = 0x03
    private const val VC_PROCESSING_UNIT = 0x05
    private const val VC_EXTENSION_UNIT = 0x06
    private const val ITT_CAMERA = 0x0201
    private const val CT_AE_MODE = 0x02
    private const val CT_EXPOSURE_ABSOLUTE = 0x04
    private const val PU_BRIGHTNESS = 0x02
    private const val PU_GAIN = 0x04
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FORMAT_FRAME_BASED = 0x10
    private const val VS_FRAME_FRAME_BASED = 0x11
    private const val VS_PROBE_CONTROL = 0x01
    private const val VS_COMMIT_CONTROL = 0x02
    private const val SET_REQUEST = 0x21 // class request to an interface, host to device
    private const val GET_REQUEST = 0xA1 // class request to an interface, device to host
    private const val SET_CUR = 0x01
    private const val GET_CUR = 0x81
    private const val GET_MIN = 0x82
    private const val GET_MAX = 0x83
    private const val GET_RES = 0x84
    private const val GET_DEF = 0x87
}
