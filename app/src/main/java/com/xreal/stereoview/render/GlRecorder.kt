package com.xreal.stereoview.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.xreal.stereoview.record.Recording
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLDisplay

/**
 * GL-thread half of a [Recording]: renders frames into each output's video encoder surface
 * (an EGL window surface sharing the renderer's context, stamped with the camera timestamp),
 * or into a framebuffer that is read back for JPEG.
 */
class GlRecorder(private val recording: Recording) {
    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private val surfaces = arrayOfNulls<EGLSurface>(recording.outputs.size)
    private val framebuffers = IntArray(recording.outputs.size)
    private val textures = IntArray(recording.outputs.size)

    init {
        val config = currentConfig()
        recording.outputs.forEachIndexed { i, o ->
            o.video?.let { encoder ->
                val surface = EGL14.eglCreateWindowSurface(display, config, encoder.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    "Cannot draw into the video encoder (EGL error 0x%x)".format(EGL14.eglGetError())
                }
                surfaces[i] = surface
            }
            if (o.jpeg != null) createFramebuffer(i, o.size.width, o.size.height)
        }
    }

    /**
     * Draws one frame of output [index] with [draw] (its argument: whether to flip vertically,
     * needed for read-back) and stores it with [timestampNs]. Returns false if it was dropped.
     */
    fun capture(index: Int, timestampNs: Long, draw: (flipY: Boolean) -> Unit): Boolean {
        val o = recording.outputs[index]
        val surface = surfaces[index]
        if (surface != null) {
            val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, o.size.width, o.size.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                draw(false)
                EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
                return EGL14.eglSwapBuffers(display, surface)
            } finally {
                EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
            }
        }
        val jpeg = o.jpeg ?: return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index])
        GLES20.glViewport(0, 0, o.size.width, o.size.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        draw(true)
        val stored = jpeg.submit(timestampNs) { buffer ->
            GLES20.glReadPixels(0, 0, o.size.width, o.size.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return stored
    }

    fun release() {
        surfaces.forEach { s -> s?.let { EGL14.eglDestroySurface(display, it) } }
        surfaces.fill(null)
        for (i in framebuffers.indices) {
            if (framebuffers[i] != 0) {
                GLES20.glDeleteFramebuffers(1, framebuffers, i)
                GLES20.glDeleteTextures(1, textures, i)
            }
        }
        framebuffers.fill(0)
    }

    /** The config of the current context, so the encoder surface is compatible with it. */
    private fun currentConfig(): EGLConfig {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
        return configs[0].takeIf { count[0] > 0 } ?: error("No EGL config for the recording surface")
    }

    private fun createFramebuffer(i: Int, width: Int, height: Int) {
        GLES20.glGenTextures(1, textures, i)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, framebuffers, i)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Picks an RGB(A)8888 ES2 config flagged EGL_RECORDABLE_ANDROID, so video encoder surfaces
     * can share the view's context; falls back to any 8-bit ES2 config.
     */
    class ConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
            for (recordable in listOf(true, false)) {
                for (alpha in listOf(8, 0)) {
                    val attributes = mutableListOf(
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, alpha,
                        EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    )
                    if (recordable) attributes += listOf(EGLExt.EGL_RECORDABLE_ANDROID, 1)
                    attributes += EGL10.EGL_NONE
                    val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                    val count = IntArray(1)
                    if (egl.eglChooseConfig(display, attributes.toIntArray(), configs, 1, count) && count[0] > 0) {
                        return configs[0]!!
                    }
                }
            }
            error("No EGL config with 8-bit color and OpenGL ES 2")
        }
    }
}
