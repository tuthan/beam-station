package io.github.tuthan.stationar.core.keys

/**
 * Which of the remote's keys Station AR keeps while an app in AR has the remote (the focus is on an app's screen).
 * Menu always opens AR's panel. Home leaves AR (left to Android, it would look for a home screen on the app's display,
 * which can't have one, and do nothing). OK and Back are held back until they're released, or held for [longMs]: held OK
 * re-centres, held Back opens the screen's menu, and a short press is sent on to the app as a whole press.
 * Everything else (the D-pad, with its auto-repeat; media keys) goes straight to the app.
 *
 * Keys Station AR sent itself come back through the filter: [onKey] passes those (`injected`) untouched. When no app
 * has the remote ([live] false), every key passes. Pure state: the caller supplies times and runs the actions.
 */
class KeyFilter(private val longMs: Long = 500) {
    enum class Action { RECENTRE, SCREEN_MENU, AR_MENU, LEAVE }

    /** What to do with one key event. */
    class Result(
        /** True: keep it from the app. */
        val consume: Boolean,
        /** Something for AR to do now, or null. */
        val action: Action? = null,
        /** A key code to send to the app as a whole press now (a short OK or Back that was held back), or 0. */
        val resend: Int = 0,
        /** When to call [onTimeout] (a held key), as a time in ms, or 0. */
        val checkAt: Long = 0,
    )

    var live = false
        set(value) {
            field = value
            if (!value) held = 0
        }

    /** The OK or Back held back now (0: none), since [heldAt]; [fired] once its long action ran. */
    private var held = 0
    private var heldAt = 0L
    private var fired = false

    fun onKey(code: Int, down: Boolean, repeat: Int, timeMs: Long, injected: Boolean): Result {
        if (!live || injected) return PASS
        when (code) {
            MENU -> return if (down && repeat == 0) Result(true, Action.AR_MENU) else CONSUME
            HOME -> return if (!down) Result(true, Action.LEAVE) else CONSUME
            OK, ENTER, BACK -> {
                if (down) {
                    // Another of them while one is held back: it goes to the app as it is (down and up), so the held
                    // one's press isn't lost.
                    if (held != 0 && held != code) return PASS
                    if (repeat > 0 || held == code) {
                        // A repeat of a key held back (from a device that repeats keys itself).
                        if (held == code && !fired && timeMs - heldAt >= longMs) return fire(code)
                        return CONSUME
                    }
                    held = code
                    heldAt = timeMs
                    fired = false
                    return Result(true, checkAt = timeMs + longMs)
                }
                // Up.
                if (held != code) return PASS   // pressed before AR had the remote: let the app see it end
                val wasLong = fired
                held = 0
                return if (wasLong) CONSUME else Result(true, resend = code)
            }
            else -> return PASS
        }
    }

    /** The time [Result.checkAt] asked for came: a key still held runs its long action. */
    fun onTimeout(timeMs: Long): Result {
        if (!live || held == 0 || fired || timeMs - heldAt < longMs) return NOTHING
        return fire(held)
    }

    private fun fire(code: Int): Result {
        fired = true
        return Result(true, if (code == BACK) Action.SCREEN_MENU else Action.RECENTRE)
    }

    companion object {
        // android.view.KeyEvent's codes (no Android here).
        const val HOME = 3
        const val BACK = 4
        const val OK = 23
        const val ENTER = 66
        const val MENU = 82

        private val PASS = Result(false)
        private val CONSUME = Result(true)
        private val NOTHING = Result(false)
    }
}
