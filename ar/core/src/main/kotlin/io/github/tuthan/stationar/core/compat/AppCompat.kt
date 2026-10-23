package io.github.tuthan.stationar.core.compat

/**
 * How well an app works in a screen in AR (a virtual display the Station's video hardware doesn't draw onto), from
 * Phase 00's test 1 (2026-10-22) and what's known about DRM. The picker sorts by it and says why.
 */
object AppCompat {
    enum class Status { WORKS, PARTLY, NO_VIDEO, DRM, UNTESTED }

    class Entry(val status: Status, val note: String)

    private val KNOWN = mapOf(
        "org.videolan.vlc" to Entry(Status.WORKS, "Plays HEVC in a screen (52-59 fps in test 1)"),
        "com.liskovsoft.smarttubetv" to Entry(Status.PARTLY, "Video showed, then stopped to buffer after the move"),
        "com.liskovsoft.smarttubetv.beta" to Entry(Status.PARTLY, "Video showed, then stopped to buffer after the move"),
        "com.teamsmart.videomanager.tv" to Entry(Status.PARTLY, "Video showed, then stopped to buffer after the move"),
        "com.google.android.youtube.tv" to Entry(Status.NO_VIDEO, "Video is solid green in a screen: play it full-screen"),
        "com.netflix.ninja" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.disney.disneyplus" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.amazon.amazonvideo.livingroom" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.wbd.stream" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.hbo.hbonow" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.apple.atve.androidtv.appletv" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "org.jellyfin.androidtv" to Entry(Status.UNTESTED, "Menus and search work in a screen; video not tried yet"),
    )

    private val UNKNOWN = Entry(Status.UNTESTED, "Not tried in a screen yet")

    fun of(pkg: String): Entry = KNOWN[pkg] ?: UNKNOWN

    /** The picker's order: what works first, then untried, then partly, then the ones that only play full-screen. */
    fun rank(pkg: String): Int = when (of(pkg).status) {
        Status.WORKS -> 0
        Status.UNTESTED -> 1
        Status.PARTLY -> 2
        Status.NO_VIDEO -> 3
        Status.DRM -> 4
    }
}
