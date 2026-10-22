package io.github.tuthan.stationar.render

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A screen's surface, centred straight ahead at [DISTANCE]: a flat quad, or a strip curved round you like a cinema
 * screen. Its model matrix (yaw and pitch) places it in the room. Vertices are x, y, z, u, v with v = 0 at the bottom.
 * Built when the layout changes, never per frame.
 */
class ScreenMesh {
    var vertices: FloatBuffer = Gl.floats(5 * 2 * (SEGMENTS + 1))
        private set
    var count = 0
        private set

    /**
     * [widthDeg] across, [aspect] width over height, flat or [curved]; [marginDeg] grows it on every side (the focus
     * frame drawn behind a screen).
     */
    fun build(widthDeg: Float, aspect: Float, curved: Boolean, marginDeg: Float = 0f) {
        val b = vertices
        b.clear()
        if (!curved) {
            val halfW = DISTANCE * tan(rad(widthDeg / 2))
            val halfH = halfW / aspect
            val m = DISTANCE * tan(rad(marginDeg))
            val x = halfW + m
            val y = halfH + m
            put(b, -x, -y, -DISTANCE, 0f, 0f)
            put(b, x, -y, -DISTANCE, 1f, 0f)
            put(b, -x, y, -DISTANCE, 0f, 1f)
            put(b, x, y, -DISTANCE, 1f, 1f)
            count = 4
        } else {
            // On a cylinder of radius DISTANCE round you: the arc is the width, so pixels stay square.
            val arc = rad(widthDeg)
            val halfH = DISTANCE * arc / aspect / 2 + DISTANCE * tan(rad(marginDeg))
            val from = -arc / 2 - rad(marginDeg)
            val span = arc + 2 * rad(marginDeg)
            for (i in 0..SEGMENTS) {
                val t = i.toFloat() / SEGMENTS
                val a = from + span * t
                val x = DISTANCE * sin(a)
                val z = -DISTANCE * cos(a)
                put(b, x, -halfH, z, t, 0f)
                put(b, x, halfH, z, t, 1f)
            }
            count = 2 * (SEGMENTS + 1)
        }
        b.position(0)
    }

    private fun put(b: FloatBuffer, x: Float, y: Float, z: Float, u: Float, v: Float) {
        b.put(x).put(y).put(z).put(u).put(v)
    }

    companion object {
        /** Metres, nominally; with one eye's view only the angles matter. */
        const val DISTANCE = 2f
        const val SEGMENTS = 32

        fun rad(deg: Float) = (deg * PI / 180).toFloat()
    }
}
