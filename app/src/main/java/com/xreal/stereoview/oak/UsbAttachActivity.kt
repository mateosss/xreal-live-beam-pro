package com.xreal.stereoview.oak

import android.app.Activity
import android.os.Bundle

/**
 * Receives USB_DEVICE_ATTACHED for OAK cameras and closes at once. Its only purpose: when the
 * user picks this app with "Always open" for the camera, Android grants USB access on every
 * attach, including the re-enumeration after the camera boots, so no prompt interrupts it.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
