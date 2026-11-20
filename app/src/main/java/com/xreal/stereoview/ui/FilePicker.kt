package com.xreal.stereoview.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Lets the user pick a file to open (e.g. a calibration to import) and hands back its URI and
 * display name. Create it as an activity field: it registers for the result at construction.
 */
class FilePicker(private val activity: ComponentActivity) {
    private var onPicked: ((Uri, String?) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onPicked
        onPicked = null
        if (uri == null || callback == null) return@registerForActivityResult
        val name = runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        callback(uri, name)
    }

    fun pick(onPicked: (Uri, String?) -> Unit) {
        this.onPicked = onPicked
        launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }
}
