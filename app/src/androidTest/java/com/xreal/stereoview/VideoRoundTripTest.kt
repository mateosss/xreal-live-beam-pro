package com.xreal.stereoview

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xreal.stereoview.preview.VideoFrameDecoder
import com.xreal.stereoview.record.EncoderOptions
import com.xreal.stereoview.record.MkvReader
import com.xreal.stereoview.record.VideoEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Gray frames (as glasses and OAK record them) → H.264 MKV → the preview's decoder. */
@RunWith(AndroidJUnit4::class)
class VideoRoundTripTest {
    @Test
    fun grayH264RecordingPlaysBackInThePreview() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "gray.mkv").apply { delete() }
        val w = 480
        val h = 640
        val written = AtomicInteger()
        val enc = VideoEncoder(file, "video/avc", w, h, 30, EncoderOptions(4_000_000), grayInput = true) { written.incrementAndGet() }
        val n = 60
        for (i in 0 until n) {
            // Left half dark, right half bright, with a level that changes per frame.
            val level = 40 + i * 2
            val px = ByteArray(w * h) { k -> (if (k % w < w / 2) level else 200).toByte() }
            while (!enc.submitGray(1_000_000_000L + i * 33_333_333L, px)) Thread.sleep(5)
            Thread.sleep(20)
        }
        enc.finish()
        val frames = MkvReader.open(file).use { it.frames.size }
        android.util.Log.i("VideoRoundTripTest", "written=${written.get()} mkvFrames=$frames applied=${enc.applied}")
        assertTrue("no frames written", written.get() > 0)
        assertEquals(written.get(), frames)
        VideoFrameDecoder(file).use { d ->
            for (i in listOf(0, frames / 2, frames - 1, 3)) {
                val b = d.frame(i, 480)
                assertNotNull("frame $i", b)
                val left = Color.red(b!!.getPixel(b.width / 4, b.height / 2))
                val right = Color.red(b.getPixel(b.width * 3 / 4, b.height / 2))
                android.util.Log.i("VideoRoundTripTest", "frame $i ${b.width}x${b.height} left=$left right=$right")
                assertTrue("frame $i right=$right", right > 180)
                assertTrue("frame $i left=$left right=$right", left < right - 30)
                // Neutral chroma everywhere, the bottom-right corner included (where a glasses
                // encoder showed green when chroma was written through the Image planes).
                for ((x, y) in listOf(b.width - 1 to b.height - 1, b.width - 20 to b.height - 2, b.width - 10 to 1, 5 to b.height - 1)) {
                    val p = b.getPixel(x, y)
                    val spread = maxOf(Color.red(p), Color.green(p), Color.blue(p)) - minOf(Color.red(p), Color.green(p), Color.blue(p))
                    assertTrue("frame $i pixel ($x,$y) not gray: ${Integer.toHexString(p)}", spread <= 6)
                }
            }
        }
    }

    /** NV12 color frames (the OAK color camera's raw frames) → H.264 MKV: colors and their place survive. */
    @Test
    fun nv12H264RecordingKeepsColors() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "nv12.mkv").apply { delete() }
        val w = 1280
        val h = 720
        val written = AtomicInteger()
        val enc = VideoEncoder(file, "video/avc", w, h, 30, EncoderOptions(20_000_000), grayInput = true) { written.incrementAndGet() }
        // Left half red (Y 76, U 85, V 255), right half blue (Y 29, U 255, V 107), full range.
        val frame = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) frame[y * w + x] = (if (x < w / 2) 76 else 29).toByte()
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            val o = w * h + y * w + 2 * x
            val red = x < w / 4
            frame[o] = (if (red) 85 else 255).toByte()
            frame[o + 1] = (if (red) 255 else 107).toByte()
        }
        for (i in 0 until 30) {
            while (!enc.submitNv12(1_000_000_000L + i * 33_333_333L, frame)) Thread.sleep(5)
            Thread.sleep(20)
        }
        enc.finish()
        val frames = MkvReader.open(file).use { it.frames.size }
        assertTrue("no frames written", written.get() > 0)
        assertEquals(written.get(), frames)
        VideoFrameDecoder(file).use { d ->
            val b = d.frame(frames - 1, 640)!!
            val left = b.getPixel(b.width / 4, b.height / 2)
            val right = b.getPixel(b.width * 3 / 4, b.height / 2)
            val corner = b.getPixel(b.width - 2, b.height - 2)
            android.util.Log.i("VideoRoundTripTest", "nv12 left=${Integer.toHexString(left)} right=${Integer.toHexString(right)} corner=${Integer.toHexString(corner)} applied=${enc.applied}")
            assertTrue("left not red: ${Integer.toHexString(left)}", Color.red(left) > 150 && Color.blue(left) < 100)
            assertTrue("right not blue: ${Integer.toHexString(right)}", Color.blue(right) > 150 && Color.red(right) < 100)
            assertTrue("corner not blue: ${Integer.toHexString(corner)}", Color.blue(corner) > 150 && Color.red(corner) < 100)
        }
    }
}
