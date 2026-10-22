package io.github.tuthan.stationar.keys

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.tuthan.stationar.AppLog

/**
 * Sees the remote's keys before any app does, so Menu, held OK and held Back still reach Station AR while an app in
 * AR has the remote. It's in `enabled_accessibility_services` only while AR is open (added and removed over the app's
 * own ADB, keeping every other entry), and outside a live AR session it passes every key on at once: the system waits
 * for it on every key.
 */
class KeyFilterService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i("keys: filter connected")
        KeyRouter.service = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (KeyRouter.service === this) KeyRouter.service = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = KeyRouter.onKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
