package io.freemytv

import android.service.dreams.DreamService

/**
 * The app's screensaver (enabled in our mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
open class Screensaver : DreamService() {
    private var view: DreamView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Watchdog.install(this)
        Ui.init(this)
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        view = DreamView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        super.onDetachedFromWindow()
    }
}
