package io.github.tuthan.stationar.sources

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import io.github.tuthan.stationar.AppLog

/**
 * An Android app's screen: a virtual display of Station AR's own (1280x720 at 213 dpi, so 960x540 dp, the usual TV
 * layout) drawn into this source's SurfaceTexture. The app is started on it by the shell helper; [listener] hears when
 * the display exists, and when it's about to go (so the app's task can be closed first, rather than fall onto the
 * glasses' own display).
 */
class VirtualDisplaySource(
    name: String,
    private val context: Context,
    private val listener: Listener,
) : SurfaceSource(name, WIDTH, HEIGHT) {
    interface Listener {
        /** The display is there (GL thread): start the app on it. */
        fun onDisplay(source: VirtualDisplaySource, displayId: Int)

        /** The display is about to be released (GL thread): close the app's task now. Blocking is fine. */
        fun onDisplayGone(source: VirtualDisplaySource, displayId: Int)
    }

    private var display: VirtualDisplay? = null

    /** The display's id, or -1 without one. */
    @Volatile var displayId = -1
        private set

    /** The app's root task on it, once started (-1 before). */
    @Volatile var task = -1

    @Synchronized
    override fun onSurface(surface: Surface) {
        display?.let {
            // A new Surface after the GL context was lost: the same display (and app) draws into it from now on.
            it.surface = surface
            return
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay("stationar-$name", WIDTH, HEIGHT, DPI, surface, FLAGS)
        display = vd
        displayId = vd.display.displayId
        AppLog.i("$name: display $displayId")
        listener.onDisplay(this, displayId)
    }

    override fun onSurfaceGone() {
        val id = displayId
        if (id >= 0) listener.onDisplayGone(this, id)
        release()
    }

    /** Releases the display now (leaving AR, from any thread); its app's task must be closed first. */
    @Synchronized
    fun release() {
        display?.release()
        display = null
        displayId = -1
        task = -1
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val DPI = 213

        /**
         * PUBLIC | OWN_CONTENT_ONLY (other apps can be started on it; it never mirrors another display) |
         * DESTROY_CONTENT_ON_REMOVAL (1 shl 8, hidden on Android 11: if Station AR dies, the apps on it close instead
         * of landing on the glasses' display).
         */
        private const val FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8)
    }
}
