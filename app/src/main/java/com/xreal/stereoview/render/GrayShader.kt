package com.xreal.stereoview.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws 8-bit grayscale images (the glasses' tracking cameras) as textured quads, scaled
 * about the viewport center. Owns one luminance texture per image slot.
 */
class GrayShader(slots: Int) {
    private val program = StereoShader.linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val uScale = GLES20.glGetUniformLocation(program, "uScale")
    private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
    private val textures = IntArray(slots)
    private val sizes = Array(slots) { intArrayOf(0, 0) }
    private var upload: ByteBuffer = ByteBuffer.allocateDirect(1)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v; image row 0 at the top
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    init {
        GLES20.glGenTextures(slots, textures, 0)
        for (t in textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    /** Uploads a [width]×[height] 8-bit image (row 0 at the top) into [slot]. */
    fun upload(slot: Int, data: ByteArray, width: Int, height: Int) {
        if (upload.capacity() < data.size) upload = ByteBuffer.allocateDirect(data.size)
        upload.clear()
        upload.put(data, 0, width * height).position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        if (sizes[slot][0] != width || sizes[slot][1] != height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, upload)
            sizes[slot][0] = width
            sizes[slot][1] = height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, upload)
        }
    }

    /** Draws [slot] over the viewport, scaled by ([scaleX], [scaleY]) about its center. */
    fun draw(slot: Int, scaleX: Float, scaleY: Float) {
        if (sizes[slot][0] == 0) return
        GLES20.glUseProgram(program)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() {
                float l = texture2D(uTexture, vUv).r;
                gl_FragColor = vec4(l, l, l, 1.0);
            }
        """
    }
}
