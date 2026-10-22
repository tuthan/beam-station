package io.github.tuthan.stationar.core.pose

import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the head's axes in the glasses' sensor frame from two still poses: looking straight ahead (where gravity
 * gives "up") and looking up (a turn about the head's "right" axis). The sensor is mounted square in the frame, so the
 * answer is snapped to the nearest signed permutation of the device axes.
 */
object AxisCalibration {
    /**
     * [up]: the accelerometer's reading looking straight ahead (it points up, against gravity), device frame.
     * [lookUp]: the rotation from looking ahead to looking up, in the device frame (conj(ahead) * up of the
     * rotation vector). Returns deviceFromHead, or null when the two don't give square axes.
     */
    fun deviceFromHead(up: Vec3, lookUp: Quat): Quat? {
        val y = snap(Vec3().set(up)) ?: return null
        // Looking up turns the head about its +X (right) axis by a positive angle.
        val axis = Vec3(lookUp.x, lookUp.y, lookUp.z)
        if (lookUp.w < 0) axis.scale(-1f)
        val x = snap(axis) ?: return null
        if (abs(x.dot(y)) > 0.5f) return null
        val z = Vec3().cross(x, y)
        return fromColumns(x, y, z)
    }

    /**
     * The same from two rotation-vector samples (sensor world from device; the world's Z is up): [ahead] looking
     * straight ahead, [up] looking up by 15° or more. Null when the turn was too small or the axes aren't square.
     */
    fun fromRotations(ahead: Quat, up: Quat): Quat? {
        val inverse = Quat().set(ahead).conjugate()
        val gravityUp = inverse.rotate(Vec3(0f, 0f, 1f), Vec3())
        val turn = Quat().multiply(inverse, up)
        // A turn under 15° is too small to tell the axis from the noise of holding still.
        if (abs(turn.w) > COS_HALF_15) return null
        return deviceFromHead(gravityUp, turn)
    }

    /** The default for the Rokid Max until a calibration says otherwise (see [DEFAULT]'s note). */
    val DEFAULT: Quat get() = default.let { Quat().set(it) }

    // Phase 00 test 4 couldn't run (no glasses connected, 2026-10-22): this assumes Android's phone convention for a
    // device whose screen faces you (X right, Y up, Z towards you), which is the head's own, so identity. The in-app
    // calibration ("look ahead, then look up") overrides it and is saved.
    private val default = Quat()

    private const val COS_HALF_15 = 0.99144486f

    /** The unit device axis nearest to v (±X, ±Y or ±Z), or null when v is too far from any (over about 35°). */
    fun snap(v: Vec3): Vec3? {
        val l = v.length()
        if (l == 0f) return null
        val ax = abs(v.x) / l
        val ay = abs(v.y) / l
        val az = abs(v.z) / l
        val out = when {
            ax >= ay && ax >= az -> Vec3(if (v.x > 0) 1f else -1f, 0f, 0f)
            ay >= az -> Vec3(0f, if (v.y > 0) 1f else -1f, 0f)
            else -> Vec3(0f, 0f, if (v.z > 0) 1f else -1f)
        }
        return if (maxOf(ax, ay, az) < 0.82f) null else out
    }

    /** The rotation whose matrix has columns x, y, z (an orthonormal right-handed basis). */
    fun fromColumns(x: Vec3, y: Vec3, z: Vec3): Quat {
        val m00 = x.x
        val m10 = x.y
        val m20 = x.z
        val m01 = y.x
        val m11 = y.y
        val m21 = y.z
        val m02 = z.x
        val m12 = z.y
        val m22 = z.z
        val trace = m00 + m11 + m22
        val q = Quat()
        if (trace > 0) {
            val s = sqrt(trace + 1f) * 2
            q.set(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2
            q.set((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2
            q.set((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2
            q.set((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
        }
        return q.normalize()
    }
}
