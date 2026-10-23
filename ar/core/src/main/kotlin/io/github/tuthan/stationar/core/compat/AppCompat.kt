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
        // Seen 2026-10-23: its player opens on the glasses' display and is moved into the screen; with SurfaceView (its
        // default) the video is black after that move, with TextureView it plays.
        "org.smarttube.stable" to Entry(Status.WORKS, SMARTTUBE),
        "com.liskovsoft.smarttubetv" to Entry(Status.WORKS, SMARTTUBE),
        "com.liskovsoft.smarttubetv.beta" to Entry(Status.WORKS, SMARTTUBE),
        "com.teamsmart.videomanager.tv" to Entry(Status.WORKS, SMARTTUBE),
        "com.google.android.youtube.tv" to Entry(Status.NO_VIDEO, "Video is solid green in a screen: play it full-screen"),
        // Seen 2026-10-23: it opens YouTube at a youtube.com link and closes; YouTube lands on the glasses' display.
        "com.google.android.youtube.tvmusic" to Entry(Status.NO_VIDEO, "Opens YouTube, which plays full-screen only"),
        "com.netflix.ninja" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.disney.disneyplus" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.amazon.amazonvideo.livingroom" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.wbd.stream" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.hbo.hbonow" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        "com.apple.atve.androidtv.appletv" to Entry(Status.DRM, "Protected video is black in a screen: play it full-screen"),
        // Seen 2026-10-23: a 10-bit HEVC film in a screen took AR down to 1-3 fps (the GPU converts every frame).
        "org.jellyfin.androidtv" to Entry(Status.PARTLY, "Plays in a screen; a 10-bit (HDR) film is too heavy there: Watch full screen"),
    )

    private const val SMARTTUBE = "Plays in a screen with Settings > Player > Developer options > " +
        "\"Ambilight/Aspect ratio/Video scale/Screenshots fix\" on (TextureView); without it the video is black"

    private val UNKNOWN = Entry(Status.UNTESTED, "Not tried in a screen yet")

    fun of(pkg: String): Entry = KNOWN[pkg] ?: UNKNOWN

    /** Whether `pkg` goes in a screen at all; the others open full screen. */
    fun inScreen(pkg: String): Boolean = of(pkg).status.let { it != Status.NO_VIDEO && it != Status.DRM }

    /** The picker's order: what works first, then untried, then partly, then the ones that only play full-screen. */
    fun rank(pkg: String): Int = when (of(pkg).status) {
        Status.WORKS -> 0
        Status.UNTESTED -> 1
        Status.PARTLY -> 2
        Status.NO_VIDEO -> 3
        Status.DRM -> 4
    }
}
