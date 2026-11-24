package com.xreal.stereoview.oak

import android.system.Os
import android.util.Log

/** The JNI glue over depthai-core (app/src/main/cpp/oak_jni.cpp); arm64 devices only. */
object OakNative {
    /** Null if the native libraries loaded, else why not (e.g. not an arm64 device). */
    val unavailableReason: String? by lazy {
        try {
            // Generous timeouts: after its firmware is uploaded the camera re-enumerates as a new
            // USB device, which Android asks the user to allow before depthai can open it.
            for (name in listOf("DEPTHAI_BOOTUP_TIMEOUT", "DEPTHAI_CONNECT_TIMEOUT", "DEPTHAI_SEARCH_TIMEOUT")) {
                Os.setenv(name, "60000", true)
            }
            Os.setenv("DEPTHAI_INSTALL_SIGNAL_HANDLER", "0", true)
            System.loadLibrary("oakjni")
            if (initialize()) null else "depthai failed to initialize"
        } catch (e: UnsatisfiedLinkError) {
            Log.w("OakNative", "Native library unavailable", e)
            "OAK support needs an arm64 device (${e.message})"
        }
    }

    @JvmStatic private external fun initialize(): Boolean

    @JvmStatic external fun start(config: IntArray): Long

    @JvmStatic external fun info(handle: Long): String

    @JvmStatic external fun pollMono(handle: Long, which: Int, out: ByteArray, meta: LongArray, timeoutMs: Int): Int

    @JvmStatic external fun pollRgb(handle: Long, out: ByteArray, meta: LongArray, timeoutMs: Int): Int

    @JvmStatic external fun pollImu(handle: Long, out: DoubleArray, timeoutMs: Int): Int

    @JvmStatic external fun setExposure(handle: Long, camera: Int, auto: Boolean, exposureUs: Int, iso: Int, compensation: Int)

    @JvmStatic external fun stop(handle: Long)
}
