package com.xreal.stereoview.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Checks whether the app can reach the glasses over USB, as Monado does for their IMU and (on
 * the Air 2 Ultra) tracking cameras: lists every USB device and interface Android exposes,
 * and, with the user's permission, tries to claim each interface of the glasses and read from
 * its endpoints.
 *
 * Interfaces are never claimed with force: that would detach the kernel driver (or the XREAL
 * service) from them and could break the glasses' display or tracking until they are
 * replugged. A failed claim therefore means "in use by someone else".
 */
class UsbProbe(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)

    /** Attached XREAL devices (several when the glasses expose more than one USB function). */
    fun glasses(): List<UsbDevice> = manager?.deviceList?.values?.filter { it.vendorId == XREAL_VENDOR }.orEmpty()

    /** Every device, configuration, interface and endpoint, without opening anything. */
    fun describe(): String = buildString {
        val host = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        appendLine("USB host feature: ${if (host) "yes" else "NO"}")
        val devices = manager?.deviceList?.values.orEmpty()
        if (devices.isEmpty()) {
            appendLine("No USB devices visible to apps.")
            appendLine("If the glasses are plugged in, the system keeps them private (or they are not in USB host mode).")
            return@buildString
        }
        for (d in devices) {
            appendLine()
            append(deviceLine(d))
            appendLine(if (manager?.hasPermission(d) == true) "  [permission granted]" else "  [no permission yet]")
            for (c in 0 until d.configurationCount) {
                val config = d.getConfiguration(c)
                appendLine("  config ${config.id}: ${config.interfaceCount} interfaces, max power ${config.maxPower} mA")
                for (i in 0 until config.interfaceCount) append(interfaceLines(config.getInterface(i)))
            }
        }
    }

    /**
     * Asks for permission to [device] (the system shows a dialog), then probes it on a
     * background thread. [onDone] gets the report on the main thread.
     */
    /** Result of [requestAndProbe]: the report, and whether a camera frame can be grabbed. */
    class Result(val report: String, val canGrab: Boolean)

    fun requestAndProbe(device: UsbDevice, onDone: (Result) -> Unit) {
        val manager = manager ?: return onDone(Result("No UsbManager", false))
        if (manager.hasPermission(device)) {
            probeInBackground(device, onDone)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    probeInBackground(device, onDone)
                } else {
                    onDone(Result("USB permission denied for ${deviceLine(device).trim()}\n\n${describe()}", false))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        // Mutable: the system adds the device and the result as extras.
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE))
    }

    private fun probeInBackground(device: UsbDevice, onDone: (Result) -> Unit) {
        val main = ContextCompat.getMainExecutor(context)
        thread(name = "UsbProbe") {
            val result = try {
                probe(device)
            } catch (e: Exception) {
                Result("Probe failed: $e", false)
            }
            main.execute { onDone(result) }
        }
    }

    /**
     * Starts the glasses' UVC camera with the standard probe/commit commands, grabs a frame
     * into [dir] and stops it again. Needs permission (granted by [requestAndProbe]).
     */
    fun grabFrame(device: UsbDevice, dir: java.io.File, onDone: (String) -> Unit) {
        val main = ContextCompat.getMainExecutor(context)
        thread(name = "UvcGrab") {
            val report = try {
                grab(device, dir)
            } catch (e: Exception) {
                "Frame grab failed: $e"
            }
            main.execute { onDone(report) }
        }
    }

    private fun grab(device: UsbDevice, dir: java.io.File): String {
        val connection = manager?.openDevice(device) ?: return "openDevice failed"
        val claimed = mutableListOf<UsbInterface>()
        try {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.alternateSetting == 0 }
            val streaming = interfaces.firstOrNull { it.interfaceSubclass == 2 } ?: return "No video streaming interface"
            val endpoint = (0 until streaming.endpointCount).map(streaming::getEndpoint)
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: return "No bulk input endpoint on the streaming interface"
            for (iface in interfaces) {
                if (!connection.claimInterface(iface, false)) return "Cannot claim video interface ${iface.id}"
                claimed += iface
            }
            val formats = Uvc.parseFormats(connection.rawDescriptors ?: ByteArray(0))
            return Uvc.grabFrame(connection, streaming, endpoint, formats, dir)
        } finally {
            claimed.forEach { connection.releaseInterface(it) }
            connection.close()
        }
    }

    /** Opens [device], tries to claim every interface and reads briefly from its IN endpoints. */
    private fun probe(device: UsbDevice): Result {
        var canGrab = false
        val report = buildString {
        appendLine("Probing ${deviceLine(device).trim()}")
        appendLine("Serial: ${runCatching { device.serialNumber }.getOrNull() ?: "-"}, version ${device.version}")
        val connection = manager?.openDevice(device)
        if (connection == null) {
            appendLine("openDevice failed: the device is held exclusively by another process.")
            return@buildString
        }
        try {
            val raw = connection.rawDescriptors ?: ByteArray(0)
            appendLine("Opened. Raw descriptors: ${raw.size} bytes")
            val formats = Uvc.parseFormats(raw)
            if (formats.isNotEmpty()) {
                appendLine()
                appendLine("Camera video formats (UVC):")
                formats.forEach { appendLine(it.describe()) }
            }
            var videoClaimed = 0
            var videoTotal = 0
            var claimed = 0
            var total = 0
            for (c in 0 until device.configurationCount) {
                val config = device.getConfiguration(c)
                for (i in 0 until config.interfaceCount) {
                    val iface = config.getInterface(i)
                    total++
                    appendLine()
                    append(interfaceLines(iface))
                    val video = iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO && iface.alternateSetting == 0
                    if (video) videoTotal++
                    if (!connection.claimInterface(iface, false)) {
                        appendLine("    claim: FAILED (in use by a kernel driver or another app)")
                        continue
                    }
                    claimed++
                    if (video) videoClaimed++
                    appendLine("    claim: OK")
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.direction != UsbConstants.USB_DIR_IN) continue
                        // A UVC stream sends nothing until started; the frame grab does that.
                        if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) continue
                        when (ep.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_ENDPOINT_XFER_INT -> {
                                val buffer = ByteArray(maxOf(ep.maxPacketSize, 64) * 4)
                                var bytes = 0
                                var reads = 0
                                var first: String? = null
                                repeat(5) {
                                    val n = connection.bulkTransfer(ep, buffer, buffer.size, 200)
                                    if (n > 0) {
                                        bytes += n
                                        reads++
                                        if (first == null) first = buffer.take(minOf(n, 24)).joinToString(" ") { "%02x".format(it) }
                                    }
                                }
                                appendLine("    read ${endpointName(ep)}: $reads of 5 reads returned data, $bytes bytes" + (first?.let { " (first: $it …)" } ?: ""))
                            }
                            UsbConstants.USB_ENDPOINT_XFER_ISOC ->
                                appendLine("    ${endpointName(ep)}: isochronous; needs native usbdevfs/libusb (not in Android's Java USB API)")
                        }
                    }
                    connection.releaseInterface(iface)
                }
            }
            appendLine()
            appendLine("Claimed $claimed of $total interfaces.")
            appendLine(
                when {
                    claimed == 0 -> "Verdict: every interface is held by the system; direct access would need it to let go (or root)."
                    claimed < total -> "Verdict: partial access. Interfaces marked OK can be used directly by the app."
                    else -> "Verdict: full access. Camera and IMU streams can be read directly by the app, given their protocol (see Monado)."
                }
            )
            canGrab = videoTotal > 0 && videoClaimed == videoTotal
        } finally {
            connection.close()
        }
        }
        return Result(report, canGrab)
    }

    private fun deviceLine(d: UsbDevice): String {
        val known = KNOWN_PRODUCTS[d.productId]?.let { " — $it" } ?: ""
        return "%04x:%04x %s %s%s (class %s, %s)\n".format(
            d.vendorId, d.productId, d.manufacturerName ?: "?", d.productName ?: "?", known,
            className(d.deviceClass), d.deviceName,
        )
    }

    private fun interfaceLines(iface: UsbInterface): String = buildString {
        append("    interface ${iface.id}/alt ${iface.alternateSetting}: ${className(iface.interfaceClass)}")
        append(" sub ${iface.interfaceSubclass} proto ${iface.interfaceProtocol}")
        iface.name?.let { append(" \"$it\"") }
        hint(iface)?.let { append(" → $it") }
        appendLine()
        for (e in 0 until iface.endpointCount) appendLine("      ${endpointName(iface.getEndpoint(e))}")
    }

    /** What an interface is likely used for. */
    private fun hint(iface: UsbInterface): String? = when (iface.interfaceClass) {
        UsbConstants.USB_CLASS_VIDEO -> "UVC video (camera)"
        UsbConstants.USB_CLASS_HID -> "HID (IMU or controls on XREAL glasses)"
        UsbConstants.USB_CLASS_VENDOR_SPEC ->
            if ((0 until iface.endpointCount).any { iface.getEndpoint(it).let { e -> e.direction == UsbConstants.USB_DIR_IN && e.type == UsbConstants.USB_ENDPOINT_XFER_BULK } }) {
                "vendor-specific with bulk input (possible camera stream)"
            } else {
                "vendor-specific"
            }
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        else -> null
    }

    private fun endpointName(e: UsbEndpoint): String {
        val type = when (e.type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
            else -> "control"
        }
        val dir = if (e.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        return "endpoint 0x%02x %s %s, %d bytes, interval %d".format(e.address, dir, type, e.maxPacketSize, e.interval)
    }

    private fun className(c: Int): String = when (c) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "per-interface"
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        UsbConstants.USB_CLASS_COMM -> "comm"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "still image"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "mass storage"
        UsbConstants.USB_CLASS_HUB -> "hub"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC data"
        UsbConstants.USB_CLASS_VIDEO -> "video"
        UsbConstants.USB_CLASS_MISC -> "misc"
        UsbConstants.USB_CLASS_APP_SPEC -> "app-specific"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "vendor-specific"
        else -> "0x%02x".format(c)
    }

    companion object {
        const val XREAL_VENDOR = 0x3318
        private const val ACTION_PERMISSION = "com.xreal.stereoview.USB_PERMISSION"

        /** Product IDs as listed by Monado's XREAL Air driver; unlisted ones show as numbers. */
        private val KNOWN_PRODUCTS = mapOf(
            0x0424 to "XREAL Air",
            0x0428 to "XREAL Air 2",
            0x0432 to "XREAL Air 2 Pro",
            0x0426 to "XREAL Air 2 Ultra",
        )
    }
}
