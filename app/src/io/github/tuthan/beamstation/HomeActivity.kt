package io.github.tuthan.beamstation

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView

/**
 * The app's home screen (Android's "Home" role in our mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
class HomeActivity : Activity() {
    private var home: HomeView? = null
    private var prefsVersion = Prefs.version   // settings changed while we were away: start over

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Watchdog.install(this)
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this)
            val t = TextView(this)
            t.text = Ui.tr("O Início do " + Config.APP_NAME + " teve um problema.\nVoltando para o Início original da TV…",
                Config.APP_NAME + "'s home screen ran into a problem.\nGoing back to the TV's original home screen…")
            t.setTextColor(Color.WHITE)
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            t.gravity = Gravity.CENTER
            t.setBackgroundColor(Color.rgb(7, 11, 30))
            setContentView(t)
            return
        }
        Ui.init(this)
        val h = HomeView(this)
        home = h
        setContentView(h)
        window.setBackgroundDrawable(null)
    }

    override fun onResume() {
        super.onResume()
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version
            HomeData.get(this).settingsChanged()
            recreate()   // a new language or city: draw everything again
            return
        }
        val h = home ?: return
        Rokid.homeResumed(this)   // before the drawer reads the glasses' mode (home.resume reloads it)
        h.resume()
    }

    override fun onPause() {
        home?.pause()
        Xgimi.paused(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Xgimi.focusChanged(this, hasFocus)
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        home?.reset(true)
    }

    override fun onDestroy() {
        home?.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) Recs.get(this).trim()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val h = home
        if (h != null && h.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
