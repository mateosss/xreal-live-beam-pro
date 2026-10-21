package com.xreal.stereoview.render

import android.opengl.GLES20
import com.xreal.stereoview.glasses.Kb4Camera
import com.xreal.stereoview.util.Mat3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws 8-bit grayscale images (the glasses' tracking cameras): either as quads scaled and
 * shifted in the viewport ([draw]), or rectified, mapping each output pixel to a ray and
 * projecting it through the camera's Kannala-Brandt fisheye model ([drawRectified]). Owns one
 * luminance texture per image slot.
 */
class GrayShader(slots: Int) {
    private val program = StereoShader.linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val uScale = GLES20.glGetUniformLocation(program, "uScale")
    private val uOffset = GLES20.glGetUniformLocation(program, "uOffset")
    private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")

    private val rectProgram = StereoShader.linkProgram(RECT_VERTEX_SHADER, RECT_FRAGMENT_SHADER)
    private val rPosition = GLES20.glGetAttribLocation(rectProgram, "aPosition")
    private val rTexCoord = GLES20.glGetAttribLocation(rectProgram, "aTexCoord")
    private val rRay = GLES20.glGetUniformLocation(rectProgram, "uRay")
    private val rIntrinsics = GLES20.glGetUniformLocation(rectProgram, "uIntrinsics")
    private val rKb4 = GLES20.glGetUniformLocation(rectProgram, "uKb4")
    private val rTexture = GLES20.glGetUniformLocation(rectProgram, "uTexture")
    private val rayGl = FloatArray(9)
    private val textures = IntArray(slots)
    private val sizes = Array(slots) { intArrayOf(0, 0) }
    private var upload: ByteBuffer = ByteBuffer.allocateDirect(1)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v; image row 0 at the top
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    private val outputQuad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v; output coordinates with v up, as StereoGeometry.outputToRay expects
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
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

    /**
     * Draws [slot] over the viewport, scaled by ([scaleX], [scaleY]) about its center and then
     * moved right by [offsetX] (in viewport widths).
     */
    fun draw(slot: Int, scaleX: Float, scaleY: Float, offsetX: Float = 0f) {
        if (sizes[slot][0] == 0) return
        GLES20.glUseProgram(program)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glUniform2f(uOffset, 2 * offsetX, 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    /**
     * Draws [slot] rectified: output (u, v, 1) → camera ray by [rayMatrix], projected through
     * [camera]'s fisheye model; rays outside the image come out black.
     */
    fun drawRectified(slot: Int, rayMatrix: DoubleArray, camera: Kb4Camera) {
        val (w, h) = sizes[slot]
        if (w == 0) return
        GLES20.glUseProgram(rectProgram)
        outputQuad.position(0)
        GLES20.glVertexAttribPointer(rPosition, 2, GLES20.GL_FLOAT, false, 16, outputQuad)
        GLES20.glEnableVertexAttribArray(rPosition)
        outputQuad.position(2)
        GLES20.glVertexAttribPointer(rTexCoord, 2, GLES20.GL_FLOAT, false, 16, outputQuad)
        GLES20.glEnableVertexAttribArray(rTexCoord)
        GLES20.glUniformMatrix3fv(rRay, 1, false, Mat3.toGl(rayMatrix, rayGl), 0)
        GLES20.glUniform4f(
            rIntrinsics, (camera.fx / w).toFloat(), (camera.fy / h).toFloat(), (camera.cx / w).toFloat(), (camera.cy / h).toFloat(),
        )
        GLES20.glUniform4f(rKb4, camera.k[0].toFloat(), camera.k[1].toFloat(), camera.k[2].toFloat(), camera.k[3].toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
        GLES20.glUniform1i(rTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(rPosition)
        GLES20.glDisableVertexAttribArray(rTexCoord)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
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

        const val RECT_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """

        const val RECT_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexture;
            uniform mat3 uRay;          // output (u, v, 1) -> camera ray
            uniform vec4 uIntrinsics;   // fx, fy, cx, cy in texture coordinates (row 0 at t = 0)
            uniform vec4 uKb4;          // k1..k4
            varying vec2 vUv;
            void main() {
                vec3 ray = uRay * vec3(vUv, 1.0);
                float r = length(ray.xy);
                float theta = atan(r, ray.z);
                if (theta > 1.5) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
                float t2 = theta * theta;
                float d = theta * (1.0 + t2 * (uKb4.x + t2 * (uKb4.y + t2 * (uKb4.z + t2 * uKb4.w))));
                vec2 n = r > 1e-6 ? ray.xy * (d / r) : vec2(0.0);
                vec2 tex = uIntrinsics.xy * n + uIntrinsics.zw;
                if (any(lessThan(tex, vec2(0.0))) || any(greaterThan(tex, vec2(1.0)))) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float l = texture2D(uTexture, tex).r;
                gl_FragColor = vec4(l, l, l, 1.0);
            }
        """
    }
}
