package io.freemytv

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.TextView

/**
 * TalkBack for the screens drawn by hand on one Canvas: every item the remote can reach becomes a virtual node with
 * its place on screen. On a TV, TalkBack moves among virtual nodes itself with the arrows; the view follows (Source.focus),
 * and when the view moves its own focus, TalkBack is told so it reads the new item.
 */
internal class Spoken(private val host: View, private val source: Source) : AccessibilityNodeProvider() {
    interface Source {
        /** Ids (positive) of the items the remote can reach now, in reading order. */
        fun items(out: MutableList<Int>)
        fun text(id: Int): String
        /** The item's bounds in the view's coordinates; false if there's nothing there now. */
        fun bounds(id: Int, out: Rect): Boolean
        /** The item with the remote's focus, or -1. */
        fun focused(): Int
        /** TalkBack moved to this item: move the remote's focus there too. */
        fun focus(id: Int)
        /** OK on this item (TalkBack's click). */
        fun click(id: Int)
        /** False for what is only there to be read (a widget): it takes the focus, so TalkBack gets to it, but OK does nothing. */
        fun clickable(id: Int): Boolean = true
    }

    private val manager = host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val ids = ArrayList<Int>()
    private val bounds = Rect()
    private val onScreen = IntArray(2)
    private val settled = Runnable { send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
    private var lastFocused = -1
    private var accessibilityFocused = -1
    private var lastText = ""
    private var following = false
    private var keyAt = 0L
    private var talkBackAt = 0L

    /** After every focus move (or change on screen): TalkBack reads the focused item if it changed. */
    fun focusMoved() {
        if (!manager.isEnabled) return
        send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        // Again once the slide is over: the items have moved to where they rest.
        host.removeCallbacks(settled)
        host.postDelayed(settled, 600)
        val f = source.focused()
        val t = if (f < 0) "" else source.text(f)
        if (f == lastFocused && t == lastText) return
        lastFocused = f
        lastText = t
        if (!following && f >= 0) send(f, AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /** The screen came back: read the focused item again once the window has focus. */
    fun readAgain() {
        lastFocused = -1
        host.postDelayed({ focusMoved() }, 400)
    }

    /** The remote's OK: false if TalkBack already clicked for this press. */
    fun keyClick(): Boolean {
        keyAt = SystemClock.uptimeMillis()
        return keyAt - talkBackAt > SAME_PRESS_MS
    }

    override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
        if (id == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            ids.clear()
            source.items(ids)
            for (item in ids) if (source.bounds(item, bounds)) info.addChild(host, item)
            return info
        }
        val text = source.text(id)
        if (text.isEmpty() || !source.bounds(id, bounds)) return null
        val clickable = source.clickable(id)
        val focused = id == source.focused()
        val info = AccessibilityNodeInfo.obtain(host, id)
        info.packageName = host.context.packageName
        info.className = (if (clickable) Button::class.java else TextView::class.java).name
        info.setParent(host)
        info.text = text
        info.setBoundsInParent(bounds)
        host.getLocationOnScreen(onScreen)
        bounds.offset(onScreen[0], onScreen[1])
        info.setBoundsInScreen(bounds)
        info.isEnabled = true
        info.isVisibleToUser = true
        info.isImportantForAccessibility = true
        info.isFocusable = true
        info.isFocused = focused
        info.isClickable = clickable
        if (clickable) info.addAction(AccessibilityAction.ACTION_CLICK)
        if (!focused) info.addAction(AccessibilityAction.ACTION_FOCUS)
        info.isAccessibilityFocused = id == accessibilityFocused
        info.addAction(if (id == accessibilityFocused) AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        return info
    }

    override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean {
        if (id == HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments)
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !source.clickable(id)) return false
        when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (accessibilityFocused == id) return false
                accessibilityFocused = id
                follow(id)
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                return true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (accessibilityFocused != id) return false
                accessibilityFocused = -1
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                return true
            }
            AccessibilityNodeInfo.ACTION_FOCUS -> {
                follow(id)
                return true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                talkBackAt = SystemClock.uptimeMillis()
                if (talkBackAt - keyAt > SAME_PRESS_MS) {
                    follow(id)
                    source.click(id)
                }
                return true
            }
            else -> return false
        }
    }

    /** Moves the view's focus to where TalkBack is, without telling TalkBack back (it's already there). */
    private fun follow(id: Int) {
        following = true
        try {
            if (source.focused() != id) source.focus(id)
        } finally {
            following = false
        }
        lastFocused = source.focused()
        lastText = if (lastFocused < 0) "" else source.text(lastFocused)
    }

    private fun send(id: Int, type: Int) {
        val parent = host.parent
        if (parent == null || !manager.isEnabled) return
        val e = AccessibilityEvent.obtain(type)
        if (id == HOST_VIEW_ID) {
            host.onInitializeAccessibilityEvent(e)
            e.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        } else {
            e.setSource(host, id)
            e.packageName = host.context.packageName
            e.className = Button::class.java.name
            e.isEnabled = true
            e.text.add(source.text(id))
        }
        parent.requestSendAccessibilityEvent(host, e)
    }

    private companion object {
        /** TalkBack's click and the remote's OK can both arrive for one press: only the first counts. */
        const val SAME_PRESS_MS = 600L
    }
}
