package io.github.tuthan.stationar.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer

/** The two shader programs: a textured surface (a bitmap or a SurfaceTexture), and a flat colour (focus frames). */
class Programs {
    /** A textured surface. [external]: the texture is a SurfaceTexture's (OES); else a plain 2D one. */
    class Textured(external: Boolean) {
        val program = Gl.program(VERTEX, if (external) FRAGMENT_OES else FRAGMENT_2D)
        private val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        private val aPos = GLES20.glGetAttribLocation(program, "aPos")
        private val aUv = GLES20.glGetAttribLocation(program, "aUv")
        private val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        private val uUvRect = GLES20.glGetUniformLocation(program, "uUvRect")
        private val uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")

        /** Draws `count` vertices (a triangle strip of x, y, z, u, v) with the texture, its matrix and part. */
        fun draw(
            vertices: FloatBuffer, count: Int, mvp: FloatArray, texture: Int, texMatrix: FloatArray,
            u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float,
        ) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, texture)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform4f(uUvRect, u0, v0, u1, v1)
            GLES20.glUniform1f(uAlpha, alpha)
            vertices.position(0)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices)
            GLES20.glEnableVertexAttribArray(aPos)
            vertices.position(3)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE, vertices)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        }
    }

    /** A flat colour. */
    class Color {
        val program = Gl.program(VERTEX_COLOR, FRAGMENT_COLOR)
        private val aPos = GLES20.glGetAttribLocation(program, "aPos")
        private val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        private val uColor = GLES20.glGetUniformLocation(program, "uColor")

        fun draw(vertices: FloatBuffer, count: Int, mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) {
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform4f(uColor, r, g, b, a)
            vertices.position(0)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count)
        }
    }

    val bitmap = Textured(external = false)
    val external = Textured(external = true)
    val color = Color()

    companion object {
        /** Bytes per vertex: x, y, z, u, v. */
        const val STRIDE = 5 * 4

        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            uniform vec4 uUvRect;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * aPos;
                vec2 uv = mix(uUvRect.xy, uUvRect.zw, aUv);
                vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_2D = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """

        private const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uAlpha;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb, uAlpha);
            }
        """

        private const val VERTEX_COLOR = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            void main() {
                gl_Position = uMvp * aPos;
            }
        """

        private const val FRAGMENT_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
