package io.github.tuthan.stationar.core.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 4x4 matrices as column-major FloatArrays of 16, OpenGL's layout (the same as android.opengl.Matrix), so they go
 * straight to glUniformMatrix4fv. Nothing here allocates.
 */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    /** out = a * b. `out` must be neither a nor b. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        require(out !== a && out !== b) { "out must differ from the inputs" }
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = s
            }
        }
    }

    /** A perspective projection, like gluPerspective: vertical field of view in degrees. */
    fun perspective(m: FloatArray, fovyDegrees: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovyDegrees * PI.toFloat() / 360f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    /** m = a translation. */
    fun setTranslation(m: FloatArray, x: Float, y: Float, z: Float) {
        identity(m)
        m[12] = x
        m[13] = y
        m[14] = z
    }

    /** m = a rotation of `degrees` about the Y axis (positive turns -Z towards -X: to the left, seen from above). */
    fun setRotationY(m: FloatArray, degrees: Float) {
        val r = degrees * PI.toFloat() / 180f
        identity(m)
        m[0] = cos(r)
        m[2] = -sin(r)
        m[8] = sin(r)
        m[10] = cos(r)
    }

    /** m = a rotation of `degrees` about the X axis (positive turns -Z towards +Y: upwards). */
    fun setRotationX(m: FloatArray, degrees: Float) {
        val r = degrees * PI.toFloat() / 180f
        identity(m)
        m[5] = cos(r)
        m[6] = sin(r)
        m[9] = -sin(r)
        m[10] = cos(r)
    }

    /** m = a scale. */
    fun setScale(m: FloatArray, x: Float, y: Float, z: Float) {
        identity(m)
        m[0] = x
        m[5] = y
        m[10] = z
    }

    /** out = the inverse of m, for a rotation plus a translation (no scale). `out` must not be m. */
    fun invertRigid(out: FloatArray, m: FloatArray) {
        require(out !== m) { "out must differ from m" }
        // The rotation part transposed; the translation rotated back and negated.
        for (col in 0 until 3) for (row in 0 until 3) out[col * 4 + row] = m[row * 4 + col]
        out[3] = 0f
        out[7] = 0f
        out[11] = 0f
        out[12] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14])
        out[13] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14])
        out[14] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14])
        out[15] = 1f
    }

    /** out = m applied to the point (x, y, z), w = 1, divided by w. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        return out.set(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[5] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w,
        )
    }
}
