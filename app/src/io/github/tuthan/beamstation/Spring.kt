package io.github.tuthan.beamstation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
internal class Spring(private val response: Float, private val damping: Float, initial: Float) {
    @JvmField var value = initial
    @JvmField var velocity = 0f
    @JvmField var target = initial

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    /** Advances `dt` seconds; returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (settled()) {
            value = target
            velocity = 0f
            return false
        }
        val omega = (2 * Math.PI / response).toFloat()
        val stiffness = omega * omega
        val friction = 2 * damping * omega
        val steps = max(1, ceil(dt / 0.004f).toInt())
        val h = dt / steps
        repeat(steps) {
            val accel = -stiffness * (value - target) - friction * velocity
            velocity += accel * h
            value += velocity * h
        }
        return true
    }

    fun settled() = abs(value - target) < 0.0008f && abs(velocity) < 0.004f
}
