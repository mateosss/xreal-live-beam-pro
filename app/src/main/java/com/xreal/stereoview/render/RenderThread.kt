package com.xreal.stereoview.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The renderer's own GL thread, in place of GLSurfaceView's: the EGL context lives as long as
 * this thread, independent of any view. With no window (screen off, app in the background) it
 * draws into a small off-screen surface, so recordings, which render into the encoders'
 * surfaces from this context, keep going; when a window is attached frames are shown there too.
 */
class RenderThread(private val renderer: StereoRenderer) {
    private val thread = HandlerThread("StereoGL", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val drawPending = AtomicBoolean(false)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSize = 1 to 1

    /** Whether frames currently reach a screen. */
    @Volatile
    var hasWindow = false
        private set

    init {
        handler.post(::createContext)
        renderer.attach(::requestRender, ::queueEvent)
    }

    fun requestRender() {
        if (drawPending.compareAndSet(false, true)) handler.post(::draw)
    }

    fun queueEvent(r: Runnable) {
        handler.post(r)
    }

    /**
     * Shows frames on [surface] (null: off-screen only). Waits until the GL thread has switched,
     * since a surface handed back by SurfaceHolder.Callback.surfaceDestroyed must not be used
     * after that call returns.
     */
    fun setWindow(surface: Surface?, width: Int, height: Int) {
        val done = CountDownLatch(1)
        handler.post {
            try {
                destroyWindow()
                if (surface != null && surface.isValid && display != EGL14.EGL_NO_DISPLAY) {
                    window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                    if (window == null || window == EGL14.EGL_NO_SURFACE) {
                        Log.w(TAG, "eglCreateWindowSurface failed: 0x%x".format(EGL14.eglGetError()))
                        window = EGL14.EGL_NO_SURFACE
                    } else {
                        windowSize = width to height
                        makeCurrent()
                        renderer.onSurfaceChanged(null, width, height)
                    }
                }
                hasWindow = window != EGL14.EGL_NO_SURFACE
            } finally {
                done.countDown()
            }
        }
        if (!done.await(2, TimeUnit.SECONDS)) Log.w(TAG, "GL thread did not switch surfaces in time")
        requestRender()
    }

    /** Releases the context and ends the thread; waits for it. */
    fun quit() {
        val done = CountDownLatch(1)
        handler.post {
            destroyWindow()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun createContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        config = chooseConfig(display)
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x%x".format(EGL14.eglGetError()) }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        makeCurrent()
        renderer.onSurfaceCreated(null, null)
    }

    private fun makeCurrent() {
        val surface = if (window != EGL14.EGL_NO_SURFACE) window else pbuffer
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.w(TAG, "eglMakeCurrent failed: 0x%x".format(EGL14.eglGetError()))
        }
    }

    private fun destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
        hasWindow = false
    }

    private fun draw() {
        drawPending.set(false)
        if (display == EGL14.EGL_NO_DISPLAY) return
        makeCurrent()
        try {
            renderer.onDrawFrame(null)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Draw failed", e)
        }
        if (window != EGL14.EGL_NO_SURFACE && !EGL14.eglSwapBuffers(display, window)) {
            val error = EGL14.eglGetError()
            Log.w(TAG, "eglSwapBuffers failed: 0x%x".format(error))
            if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) destroyWindow()
        }
    }

    private companion object {
        const val TAG = "RenderThread"

        /**
         * An RGB(A)8888 ES2 config flagged EGL_RECORDABLE_ANDROID, so video encoder surfaces
         * can share the context; falls back to any 8-bit ES2 config.
         */
        fun chooseConfig(display: EGLDisplay): EGLConfig {
            for (recordable in listOf(true, false)) {
                for (alpha in listOf(8, 0)) {
                    val attributes = mutableListOf(
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, alpha,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    )
                    if (recordable) attributes += listOf(EGLExt.EGL_RECORDABLE_ANDROID, 1)
                    attributes += EGL14.EGL_NONE
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val count = IntArray(1)
                    if (EGL14.eglChooseConfig(display, attributes.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0) {
                        return configs[0]!!
                    }
                }
            }
            error("No EGL config with 8-bit color and OpenGL ES 2")
        }
    }
}
