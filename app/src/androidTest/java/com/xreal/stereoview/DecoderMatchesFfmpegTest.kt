package com.xreal.stereoview

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xreal.stereoview.preview.VideoFrameDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dumps frames of /data/local/tmp/check.mkv decoded by the preview, for comparison with ffmpeg. */
@RunWith(AndroidJUnit4::class)
class DecoderMatchesFfmpegTest {
    @Test
    fun dumpFrames() {
        val src = File("/data/local/tmp/check.mkv")
        assumeTrue(src.canRead())
        val out = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        VideoFrameDecoder(src).use { d ->
            for (i in listOf(0, 10, 45)) {
                val b = d.frame(i, 1 shl 14) ?: continue
                File(out, "check_$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
