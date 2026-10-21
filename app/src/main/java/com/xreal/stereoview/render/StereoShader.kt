package com.xreal.stereoview.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.xreal.stereoview.camera.CameraModel
import com.xreal.stereoview.util.Mat3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws one camera texture into the current viewport (one eye of the SBS frame).
 *
 * Every output pixel is mapped to a ray by [draw]'s ray matrix (see [StereoGeometry]), then
 * projected through the camera's lens distortion and intrinsics into the stream image. This is
 * done per fragment because the mapping is projective, not affine. Rays that miss the camera
 * image come out black.
 */
class StereoShader {
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uRay: Int
    private val uIntrinsics: Int
    private val uRadial: Int
    private val uTangential: Int
    private val uMaxRadius2: Int
    private val uStMatrix: Int
    private val uTexture: Int
    private val rayGl = FloatArray(9)

    // Pass-through program for raw recordings: the stream image exactly as delivered.
    private val rawProgram: Int
    private val rawPosition: Int
    private val rawTexCoord: Int
    private val rawStMatrix: Int
    private val rawFlip: Int
    private val rawTexture: Int

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
        uRay = GLES20.glGetUniformLocation(program, "uRay")
        uIntrinsics = GLES20.glGetUniformLocation(program, "uIntrinsics")
        uRadial = GLES20.glGetUniformLocation(program, "uRadial")
        uTangential = GLES20.glGetUniformLocation(program, "uTangential")
        uMaxRadius2 = GLES20.glGetUniformLocation(program, "uMaxRadius2")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        rawProgram = linkProgram(VERTEX_SHADER, RAW_FRAGMENT_SHADER)
        rawPosition = GLES20.glGetAttribLocation(rawProgram, "aPosition")
        rawTexCoord = GLES20.glGetAttribLocation(rawProgram, "aTexCoord")
        rawStMatrix = GLES20.glGetUniformLocation(rawProgram, "uStMatrix")
        rawFlip = GLES20.glGetUniformLocation(rawProgram, "uFlip")
        rawTexture = GLES20.glGetUniformLocation(rawProgram, "uTexture")
    }

    /**
     * Draws the stream image unchanged into the viewport, top row at the top; with [flipY] the
     * top row lands at the bottom instead (row 0 of a glReadPixels).
     */
    fun drawRaw(texture: Int, stMatrix: FloatArray, flipY: Boolean) {
        GLES20.glUseProgram(rawProgram)
        bindQuad(rawPosition, rawTexCoord)
        GLES20.glUniformMatrix4fv(rawStMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform1f(rawFlip, if (flipY) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(rawTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(rawPosition)
        GLES20.glDisableVertexAttribArray(rawTexCoord)
    }

    private fun bindQuad(position: Int, texCoord: Int) {
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(texCoord)
    }

    fun draw(texture: Int, rayMatrix: DoubleArray, camera: CameraModel, stMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        bindQuad(aPosition, aTexCoord)

        GLES20.glUniformMatrix3fv(uRay, 1, false, Mat3.toGl(rayMatrix, rayGl), 0)
        GLES20.glUniform4f(uIntrinsics, camera.fx.toFloat(), camera.fy.toFloat(), camera.cx.toFloat(), camera.cy.toFloat())
        GLES20.glUniform3f(uRadial, camera.radial[0].toFloat(), camera.radial[1].toFloat(), camera.radial[2].toFloat())
        GLES20.glUniform2f(uTangential, camera.tangential[0].toFloat(), camera.tangential[1].toFloat())
        GLES20.glUniform1f(uMaxRadius2, camera.maxRadiusSquared.toFloat())
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)

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
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform mat3 uRay;          // output (u, v, 1) -> camera ray
            uniform vec4 uIntrinsics;   // fx, fy, cx, cy in normalized image coords (y down)
            uniform vec3 uRadial;       // k1, k2, k3
            uniform vec2 uTangential;   // p1, p2
            uniform float uMaxRadius2;
            uniform mat4 uStMatrix;
            varying vec2 vUv;
            const vec4 BLACK = vec4(0.0, 0.0, 0.0, 1.0);
            void main() {
                vec3 ray = uRay * vec3(vUv, 1.0);
                if (ray.z <= 0.0) { gl_FragColor = BLACK; return; }
                vec2 n = ray.xy / ray.z;
                float r2 = dot(n, n);
                if (r2 > uMaxRadius2) { gl_FragColor = BLACK; return; }
                float radial = 1.0 + r2 * (uRadial.x + r2 * (uRadial.y + r2 * uRadial.z));
                vec2 d = n * radial + vec2(
                    2.0 * uTangential.x * n.x * n.y + uTangential.y * (r2 + 2.0 * n.x * n.x),
                    uTangential.x * (r2 + 2.0 * n.y * n.y) + 2.0 * uTangential.y * n.x * n.y);
                vec2 image = uIntrinsics.xy * d + uIntrinsics.zw;
                if (any(lessThan(image, vec2(0.0))) || any(greaterThan(image, vec2(1.0)))) {
                    gl_FragColor = BLACK;
                    return;
                }
                vec2 tex = (uStMatrix * vec4(image.x, 1.0 - image.y, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(uTexture, tex);
            }
        """

        private const val RAW_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uStMatrix;
            uniform float uFlip;
            varying vec2 vUv;
            void main() {
                vec2 uv = vec2(vUv.x, mix(vUv.y, 1.0 - vUv.y, uFlip));
                gl_FragColor = texture2D(uTexture, (uStMatrix * vec4(uv, 0.0, 1.0)).xy);
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

        fun linkProgram(vertexSource: String, fragmentSource: String): Int {
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
