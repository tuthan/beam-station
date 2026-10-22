package io.github.tuthan.stationar.core.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mutable rotation quaternion (w + xi + yj + zk). Every operation works in place and returns `this`, so the sensor
 * and frame loops allocate nothing. Composition follows the usual convention: `a * b` rotates by b first, then a.
 */
class Quat(
    @JvmField var w: Float = 1f,
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
) {
    fun set(w: Float, x: Float, y: Float, z: Float): Quat {
        this.w = w
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(q: Quat): Quat = set(q.w, q.x, q.y, q.z)

    fun identity(): Quat = set(1f, 0f, 0f, 0f)

    /** A rotation of `radians` about the axis (ax, ay, az), which needn't be unit length; a zero axis gives identity. */
    fun setAxisAngle(ax: Float, ay: Float, az: Float, radians: Float): Quat {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l == 0f) return identity()
        val s = sin(radians / 2) / l
        return set(cos(radians / 2), ax * s, ay * s, az * s)
    }

    /** this = a * b (safe when this is a or b). */
    fun multiply(a: Quat, b: Quat): Quat = set(
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    )

    /** The inverse rotation (for a unit quaternion). */
    fun conjugate(): Quat = set(w, -x, -y, -z)

    fun dot(q: Quat): Float = w * q.w + x * q.x + y * q.y + z * q.z

    fun normalize(): Quat {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return if (l == 0f) identity() else set(w / l, x / l, y / l, z / l)
    }

    /** out = this rotating v (safe when out is v). */
    fun rotate(v: Vec3, out: Vec3): Vec3 {
        // v' = v + 2w(q × v) + 2 q × (q × v), with q the vector part.
        val tx = 2 * (y * v.z - z * v.y)
        val ty = 2 * (z * v.x - x * v.z)
        val tz = 2 * (x * v.y - y * v.x)
        return out.set(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    /**
     * Turns this on by an angular rate (wx, wy, wz) in rad/s held for `seconds`, measured in this rotation's own
     * frame (a gyroscope's): this = this * exp(ω t / 2).
     */
    fun integrate(wx: Float, wy: Float, wz: Float, seconds: Float): Quat {
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        val angle = rate * seconds
        if (angle == 0f) return this
        val s = sin(angle / 2) / rate
        val dw = cos(angle / 2)
        val dx = wx * s
        val dy = wy * s
        val dz = wz * s
        return set(
            w * dw - x * dx - y * dy - z * dz,
            w * dx + x * dw + y * dz - z * dy,
            w * dy - x * dz + y * dw + z * dx,
            w * dz + x * dy - y * dx + z * dw,
        ).normalize()
    }

    /** Writes the rotation as a column-major 4x4 matrix (OpenGL's layout) at `offset`. */
    fun toMatrix(m: FloatArray, offset: Int = 0) {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        m[offset] = 1 - 2 * (yy + zz)
        m[offset + 1] = 2 * (xy + wz)
        m[offset + 2] = 2 * (xz - wy)
        m[offset + 3] = 0f
        m[offset + 4] = 2 * (xy - wz)
        m[offset + 5] = 1 - 2 * (xx + zz)
        m[offset + 6] = 2 * (yz + wx)
        m[offset + 7] = 0f
        m[offset + 8] = 2 * (xz + wy)
        m[offset + 9] = 2 * (yz - wx)
        m[offset + 10] = 1 - 2 * (xx + yy)
        m[offset + 11] = 0f
        m[offset + 12] = 0f
        m[offset + 13] = 0f
        m[offset + 14] = 0f
        m[offset + 15] = 1f
    }

    override fun toString() = "Quat($w, $x, $y, $z)"
}
