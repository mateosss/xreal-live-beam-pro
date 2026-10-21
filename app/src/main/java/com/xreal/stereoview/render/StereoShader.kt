package com.xreal.stereoview.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws one camera texture into the current viewport (one eye of the SBS frame).
 *
 * The quad's texture coordinates are in screen space; [draw]'s view matrix maps them into
 * camera-image space (convergence shift, depth zoom, aspect crop, rotation), then the
 * SurfaceTexture matrix maps them into the buffer. Samples falling outside the camera image,
 * e.g. after a convergence shift, come out black instead of smearing the edge pixels.
 */
class StereoShader {
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uViewMatrix: Int
    private val uStMatrix: Int
    private val uVertexScale: Int
    private val uTexture: Int

    private val quad: FloatBuffer = floatBuffer(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    init {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uViewMatrix = GLES20.glGetUniformLocation(program, "uViewMatrix")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uVertexScale = GLES20.glGetUniformLocation(program, "uVertexScale")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
    }

    fun draw(texture: Int, viewMatrix: FloatArray, stMatrix: FloatArray, scaleX: Float, scaleY: Float) {
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uViewMatrix, 1, false, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uVertexScale, scaleX, scaleY)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    companion object {
        private const val STRIDE = 4 * 4

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uViewMatrix;
            uniform mat4 uStMatrix;
            uniform vec2 uVertexScale;
            varying vec2 vCamCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uVertexScale, 0.0, 1.0);
                vec4 cam = uViewMatrix * vec4(aTexCoord, 0.0, 1.0);
                vCamCoord = cam.xy;
                vTexCoord = (uStMatrix * cam).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vCamCoord;
            varying vec2 vTexCoord;
            void main() {
                if (any(lessThan(vCamCoord, vec2(0.0))) || any(greaterThan(vCamCoord, vec2(1.0)))) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            }
        """

        fun createExternalTexture(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return tex[0]
        }

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return shader
        }

        private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSource))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw IllegalStateException("Program link failed: $log")
            }
            return program
        }
    }
}
