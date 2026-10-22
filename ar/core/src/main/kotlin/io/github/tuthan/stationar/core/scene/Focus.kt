package io.github.tuthan.stationar.core.scene

import kotlin.math.abs

/**
 * Look-to-focus: the screen you look at becomes the focused one once your gaze has stayed on it for [dwellMs]. The
 * gaze is "on" a screen inside its edges plus [marginDeg]; where two overlap, the one whose centre is nearest wins.
 * Looking at nothing keeps the last focus (so a glance at the wall doesn't take the remote away from the screen).
 *
 * Called every frame on the render thread: nothing is allocated.
 */
class Focus(var dwellMs: Long = 300, var marginDeg: Float = 2f) {
    /** The focused screen's index in the layout, or -1. */
    var focused = -1
        private set

    private var candidate = -1
    private var since = 0L

    /** Clears the focus (a new layout). The next [update] focuses at once. */
    fun reset() {
        focused = -1
        candidate = -1
    }

    /** Sets the focus at once (a new screen was opened in front of you, say). */
    fun set(index: Int) {
        focused = index
        candidate = index
    }

    /** The screen under the gaze now, or -1. */
    fun hit(screens: List<ScreenSpec>, yaw: Float, pitch: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in screens.indices) {
            val s = screens[i]
            val dy = angle(yaw - s.yaw)
            val dp = pitch - s.pitch
            if (abs(dy) > s.width / 2 + marginDeg || abs(dp) > s.height / 2 + marginDeg) continue
            val d = dy * dy + dp * dp
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Feeds the gaze; returns true when the focus changed. */
    fun update(screens: List<ScreenSpec>, yaw: Float, pitch: Float, nowMs: Long): Boolean {
        if (focused >= screens.size) focused = -1
        val hit = hit(screens, yaw, pitch)
        if (hit < 0 || hit == focused) {
            candidate = hit
            return false
        }
        if (focused < 0) {
            set(hit)
            return true
        }
        if (hit != candidate) {
            candidate = hit
            since = nowMs
            return false
        }
        if (nowMs - since >= dwellMs) {
            focused = hit
            return true
        }
        return false
    }

    companion object {
        /** An angle difference folded into -180..180. */
        fun angle(d: Float): Float {
            var a = d % 360f
            if (a > 180f) a -= 360f
            if (a < -180f) a += 360f
            return a
        }
    }
}
