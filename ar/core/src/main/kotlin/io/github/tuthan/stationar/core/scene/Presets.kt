package io.github.tuthan.stationar.core.scene

import kotlin.math.abs

/**
 * The layouts the AR menu offers. Angular sizes assume the Rokid Max: about 44° across for 1920 px, so a laptop
 * monitor at 1:1 (every pixel one pixel on the glasses, the sharpest) is [ONE_TO_ONE] wide.
 */
object Presets {
    const val CINEMA = "cinema"
    const val DESK = "desk"
    const val DESK_APP = "desk-app"
    const val LAST = "last"

    /** Degrees for 1920 px at 1:1 on the Max (1920 px over a 44.2° wide field). */
    const val ONE_TO_ONE = 44.2f

    val NAMES = listOf(CINEMA, DESK, DESK_APP)

    fun label(name: String) = when (name) {
        CINEMA -> "Cinema"
        DESK -> "Desk"
        DESK_APP -> "Desk + app"
        else -> "Custom"
    }

    /** What a preset's tile says under its name; the laptop ones say so when the laptop isn't streaming. */
    fun detail(name: String, laptop: Boolean) = when (name) {
        CINEMA -> "one big screen"
        DESK -> if (laptop) "2 laptop screens" else "needs the laptop"
        DESK_APP -> if (laptop) "laptop + app" else "needs the laptop"
        else -> ""
    }

    /**
     * What Cinema's one screen shows: the screen you look at (`focused`) if it's an app or a laptop that's streaming;
     * else the first app screen of `screens`; else a streaming laptop; else the test card. Never a laptop that isn't
     * there, which would only wait. `streaming`: the laptop sources connected now.
     */
    fun cinemaSource(focused: String?, screens: List<String>, streaming: Set<String>): String {
        if (focused != null && (focused.startsWith("app:") || focused in streaming)) return focused
        screens.firstOrNull { it.startsWith("app:") }?.let { return it }
        return listOf("laptop:0", "laptop:1").firstOrNull { it in streaming } ?: ScreenSpec.CARD
    }

    /**
     * The preset's screens. [first] is what the main screen shows (the test card until a laptop or an app is there);
     * [app] the app screen's source in Desk + app.
     */
    fun layout(name: String, first: String = ScreenSpec.CARD, app: String = ScreenSpec.CARD): Layout? = when (name) {
        // One big screen straight ahead, curved like a cinema's.
        CINEMA -> Layout(mutableListOf(ScreenSpec(first, 0f, 0f, 60f, 16f / 9f, curved = true)))
        // Two laptop monitors side by side at 1:1, meeting straight ahead.
        DESK -> Layout(
            mutableListOf(
                ScreenSpec("laptop:0", -ONE_TO_ONE / 2 - 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec("laptop:1", ONE_TO_ONE / 2 + 0.5f, 0f, ONE_TO_ONE),
            ),
        )
        // The two monitors, and an app screen above them.
        DESK_APP -> Layout(
            mutableListOf(
                ScreenSpec("laptop:0", -ONE_TO_ONE / 2 - 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec("laptop:1", ONE_TO_ONE / 2 + 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec(app, 0f, 23.5f, 36f),
            ),
        )
        else -> null
    }

    /** The default when nothing was saved: the test card straight ahead, 40° wide. */
    fun welcome() = Layout(mutableListOf(ScreenSpec(ScreenSpec.CARD, 0f, 0f, 40f)))

    /**
     * The layout with the screen(s) of a laptop stream that connected while no screen showed it: where Desk puts
     * them, at 1:1 for the stream's size (`width` x `height` pixels; a `wide` stream is two halves side by side).
     * Test cards in the way go. Null when that would make more than [Layout.MAX_SCREENS].
     */
    fun place(layout: Layout, source: String, width: Int, height: Int): Layout? {
        val adds = when (source) {
            "wide" -> {
                val w = (width / 2) * ONE_TO_ONE / 1920f
                val aspect = (width / 2f) / height
                listOf(ScreenSpec("wide:0", -w / 2 - 0.5f, 0f, w, aspect), ScreenSpec("wide:1", w / 2 + 0.5f, 0f, w, aspect))
            }
            "laptop:0", "laptop:1" -> {
                val w = width * ONE_TO_ONE / 1920f
                val side = if (source == "laptop:0") -1 else 1
                listOf(ScreenSpec(source, side * (w / 2 + 0.5f), 0f, w, width.toFloat() / height))
            }
            else -> return null
        }
        val out = layout.copy()
        out.screens.removeAll { s -> s.kind == "card" && adds.any { overlap(it, s) } }
        for (a in adds) if (out.indexOf(a.source) < 0) out.screens.add(a)
        return if (out.screens.size > Layout.MAX_SCREENS) null else out
    }

    /**
     * The layout with the screens that show a laptop stream shaped like its picture (`width` x `height`; each half of a
     * `wide` stream half as wide), each keeping its width: a 21:9 monitor isn't squashed into a 16:9 screen. Null when
     * they have that shape already.
     */
    fun fit(layout: Layout, source: String, width: Int, height: Int): Layout? {
        if (width <= 0 || height <= 0) return null
        val aspect = if (source == "wide") (width / 2f) / height else width.toFloat() / height
        val out = layout.copy()
        var changed = false
        for (s in out.screens) {
            val shows = if (source == "wide") s.kind == "wide" else s.source == source
            if (shows && abs(s.aspect - aspect) > 1e-3f) {
                s.aspect = aspect
                changed = true
            }
        }
        return if (changed) out else null
    }

    /** An app screen's width: 1280x720 at about 1.5 glasses pixels per app pixel. */
    const val APP_WIDTH = 36f

    /**
     * The layout with an app's screen (`app:<package>`) added: in place of a test card you look at, else where you
     * look if that's free, else the first free place to its right. The same layout if it's there already; null when
     * full.
     */
    fun placeApp(layout: Layout, source: String, gazeYaw: Float): Layout? {
        val out = layout.copy()
        if (out.indexOf(source) >= 0) return out
        val card = out.screens.indexOfFirst { it.kind == "card" && abs(Focus.angle(it.yaw - gazeYaw)) < it.width / 2 }
        if (card >= 0) {
            val c = out.screens[card]
            out.screens[card] = ScreenSpec(source, c.yaw, c.pitch, APP_WIDTH)
            return out
        }
        if (out.screens.size >= Layout.MAX_SCREENS) return null
        for (step in 0 until 18) {
            val s = ScreenSpec(source, Focus.angle(gazeYaw + step * 10f), 0f, APP_WIDTH)
            if (out.screens.none { overlap(it, s) }) {
                out.screens.add(s)
                return out
            }
        }
        return null
    }

    private fun overlap(a: ScreenSpec, b: ScreenSpec) =
        abs(Focus.angle(a.yaw - b.yaw)) < (a.width + b.width) / 2 && abs(a.pitch - b.pitch) < (a.height + b.height) / 2
}
