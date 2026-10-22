package io.github.tuthan.stationar.keys

import android.view.KeyEvent

/**
 * Where the key filter sends keys. Until an AR session hands it a [Handler], every key passes straight on.
 */
object KeyRouter {
    /** Takes a key while an app in AR has the remote; returns true to keep it from the app. Main thread. */
    fun interface Handler {
        fun onKey(event: KeyEvent): Boolean
    }

    @Volatile var service: KeyFilterService? = null
    @Volatile var handler: Handler? = null

    fun onKey(event: KeyEvent): Boolean = handler?.onKey(event) ?: false
}
