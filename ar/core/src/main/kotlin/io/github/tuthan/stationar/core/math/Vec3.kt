package io.github.tuthan.stationar.core.math

import kotlin.math.sqrt

/** A mutable 3-vector. Every operation works in place and returns `this`, so the frame loop allocates nothing. */
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(v: Vec3): Vec3 = set(v.x, v.y, v.z)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun dot(v: Vec3): Float = x * v.x + y * v.y + z * v.z

    /** Unit length; a zero vector stays zero. */
    fun normalize(): Vec3 {
        val l = length()
        if (l > 0f) {
            x /= l
            y /= l
            z /= l
        }
        return this
    }

    /** this = a × b (safe when this is a or b). */
    fun cross(a: Vec3, b: Vec3): Vec3 = set(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    fun scale(s: Float): Vec3 = set(x * s, y * s, z * s)

    override fun toString() = "($x, $y, $z)"
}
