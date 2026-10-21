package com.xreal.stereoview.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal USB Video Class support for probing the glasses' camera: decoding the video formats
 * from the configuration descriptors, and grabbing one frame from a bulk streaming endpoint
 * with the standard probe/commit negotiation.
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
        val index = streaming.id
        val probe = ByteArray(48)
        val length = connection.controlTransfer(GET_REQUEST, GET_CUR, VS_PROBE_CONTROL shl 8, index, probe, probe.size, 1000)
        if (length < 26) {
            appendLine("GET_CUR probe failed ($length)")
            return@buildString
        }
        appendLine("probe control: $length bytes (UVC ${if (length >= 34) "1.1+" else "1.0"})")
        val format = formats.firstOrNull { it.interfaceNumber == index } ?: formats.firstOrNull()
        val frame = format?.frames?.firstOrNull()
        val request = probe.copyOf(length)
        if (format != null && frame != null) {
            request[2] = format.index.toByte()
            request[3] = frame.index.toByte()
            request.putU32(4, frame.defaultInterval)
        }
        if (connection.controlTransfer(SET_REQUEST, SET_CUR, VS_PROBE_CONTROL shl 8, index, request, length, 1000) < 0) {
            appendLine("SET_CUR probe failed")
            return@buildString
        }
        val negotiated = ByteArray(length)
        connection.controlTransfer(GET_REQUEST, GET_CUR, VS_PROBE_CONTROL shl 8, index, negotiated, length, 1000)
        val formatIndex = negotiated[2].u8()
        val frameIndex = negotiated[3].u8()
        val chosen = formats.firstOrNull { it.interfaceNumber == index && it.index == formatIndex }
        val size = chosen?.frames?.firstOrNull { it.index == frameIndex }
        val frameBytes = negotiated.u32(18)
        val payload = negotiated.u32(22).coerceIn(512, 4 * 1024 * 1024)
        appendLine(
            "negotiated: format $formatIndex frame $frameIndex (${size?.let { "${it.width}×${it.height}" } ?: "?"}), " +
                "%.1f fps, frame %d bytes, payload %d bytes".format(
                    negotiated.u32(4).takeIf { it > 0 }?.let { 1e7 / it } ?: 0.0, frameBytes, payload,
                ),
        )
        if (connection.controlTransfer(SET_REQUEST, SET_CUR, VS_COMMIT_CONTROL shl 8, index, negotiated, length, 1000) < 0) {
            appendLine("SET_CUR commit failed")
            return@buildString
        }

        val buffer = ByteArray(payload)
        val current = ByteArrayOutputStream()
        val frames = mutableListOf<ByteArray>()
        var lastFid = -1
        var reads = 0
        var errors = 0
        val start = System.nanoTime()
        val frameTimes = mutableListOf<Long>()
        while (frames.size < 3 && System.nanoTime() - start < 3_000_000_000L) {
            val n = connection.bulkTransfer(endpoint, buffer, buffer.size, 500)
            reads++
            if (n <= 0) {
                errors++
                continue
            }
            val header = buffer[0].u8()
            if (header < 2 || header > n) {
                current.write(buffer, 0, n) // no header: raw data
                continue
            }
            val info = buffer[1].u8()
            val fid = info and 0x01
            if (lastFid >= 0 && fid != lastFid && current.size() > 0) {
                frames += current.toByteArray().also { frameTimes += System.nanoTime() }
                current.reset()
            }
            lastFid = fid
            if (info and 0x40 != 0) errors++
            current.write(buffer, header, n - header)
            if (info and 0x02 != 0) {
                frames += current.toByteArray().also { frameTimes += System.nanoTime() }
                current.reset()
            }
        }
        connection.setInterface(streaming) // alternate setting 0 stops the stream
        appendLine("reads: $reads, errors/timeouts: $errors, frames: ${frames.size} in %.2f s".format((System.nanoTime() - start) / 1e9))
        if (frameTimes.size >= 2) {
            appendLine("frame period ≈ %.1f ms".format((frameTimes.last() - frameTimes.first()) / 1e6 / (frameTimes.size - 1)))
        }
        val picked = frames.getOrNull(1) ?: frames.firstOrNull() ?: return@buildString
        dir.mkdirs()
        val name = "glasses_frame_${size?.width ?: 0}x${size?.height ?: 0}_${chosen?.fourcc ?: "unknown"}"
        File(dir, "$name.bin").writeBytes(picked)
        appendLine("frame: ${picked.size} bytes (expected $frameBytes), first bytes ${picked.take(16).joinToString(" ") { "%02x".format(it) }}")
        appendLine("saved ${File(dir, "$name.bin")}")
        // Also write the bytes as 8-bit grayscale images anyone can open. A "YUY2" stream may
        // really be packed grayscale (e.g. tracking cameras), so try both plausible layouts.
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
    private const val SUBCLASS_STREAMING = 0x02
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
}
