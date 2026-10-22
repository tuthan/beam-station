package io.github.tuthan.stationar.sources

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import io.github.tuthan.stationar.AppLog

/**
 * An Android app's screen: a virtual display of Station AR's own, at the glasses' display's density (so an app moving
 * between the two isn't restarted: VLC's player, for one, closes when Android restarts it for a new density) and at
 * most 1280x720 (composing a bigger one costs AR frames on the Mali-G31), drawn into this source's SurfaceTexture. The app is started on it by the shell helper; [listener]
 * hears when the display exists, and when it's about to go (so the app's task can be closed first, rather than fall
 * onto the glasses' own display).
 */
class VirtualDisplaySource(
    name: String,
    private val context: Context,
    private val listener: Listener,
    val size: Size,
) : SurfaceSource(name, size.width, size.height) {
    /** A display's size in pixels and its density. */
    class Size(val width: Int, val height: Int, val dpi: Int)

    interface Listener {
        /** The display is there (GL thread): start the app on it. */
        fun onDisplay(source: VirtualDisplaySource, displayId: Int)

        /**
         * The screen went (GL thread, don't block): the display has no Surface any more, and the app runs on, drawing
         * nowhere. Close its task, then [release] the display (elsewhere; the task first, or it lands on the glasses).
         */
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
        val vd = dm.createVirtualDisplay("stationar-$name", size.width, size.height, size.dpi, surface, FLAGS)
        display = vd
        displayId = vd.display.displayId
        AppLog.i("$name: display $displayId")
        listener.onDisplay(this, displayId)
    }

    override fun onSurfaceGone() {
        val id: Int
        synchronized(this) {
            id = displayId
            display?.surface = null
        }
        if (id >= 0) listener.onDisplayGone(this, id) else release()
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
        /** The Station on the Rokid Max: 240 dpi, as 1280x720 (853x480 dp). */
        private val FALLBACK = Size(1280, 720, 240)
        private const val MAX_WIDTH = 1280

        /** An app screen for the glasses' display (display 0): its density, landscape, at most [MAX_WIDTH] wide. */
        fun glassesSize(c: Context): Size {
            val d = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return FALLBACK
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(m)
            val w = maxOf(m.widthPixels, m.heightPixels)
            val h = minOf(m.widthPixels, m.heightPixels)
            if (w !in 640..3840 || h !in 360..2160 || m.densityDpi !in 120..640) return FALLBACK
            return if (w <= MAX_WIDTH) Size(w, h, m.densityDpi) else Size(MAX_WIDTH, h * MAX_WIDTH / w, m.densityDpi)
        }

        /**
         * PUBLIC | OWN_CONTENT_ONLY (other apps can be started on it; it never mirrors another display) |
         * DESTROY_CONTENT_ON_REMOVAL (1 shl 8, hidden on Android 11: if Station AR dies, the apps on it close instead
         * of landing on the glasses' display).
         */
        private const val FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8)
    }
}
