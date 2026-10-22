package io.github.tuthan.stationar.glasses

import android.content.Context
import android.hardware.usb.UsbManager
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.core.store.GlassesState
import io.github.tuthan.stationar.core.store.KvStore
import java.io.File

/**
 * The glasses while AR is open: 2D (AR is mono), and 60 Hz unless asked otherwise. What they were set to is saved to a
 * file *before* anything changes; leaving AR puts the refresh rate back and leaves them in 2D (Station Home puts 2D
 * back on every resume anyway, so restoring 3D would only cost a second HDMI retrain). If the app dies in AR, the next
 * start finds the file and restores from it. Every call blocks (HDMI takes seconds to settle): never on the main thread.
 */
class GlassesSession(context: Context) {
    private val app = context.applicationContext
    private val display = RokidDisplay(app)
    private val store = KvStore(File(app.filesDir, "glasses-restore.txt"))

    /** Switches for AR; returns what the glasses are at now, or null without glasses. */
    fun enter(want60: Boolean): RokidDisplay.State? {
        // rokid_display answers for any HDMI screen; only the glasses' own USB says they're the ones plugged in.
        if (!plugged(app)) return null.also { AppLog.i("glasses: not plugged in (an HDMI screen?): left as they are") }
        val now = display.read() ?: return null.also { AppLog.i("glasses: rokid_display unreachable") }
        // A file left by a crash holds the state from before that AR session: keep it, it's the one to go back to.
        if (GlassesState.load(store) == null) GlassesState(now.mode, now.fps).save(store)
        val (leave3d, fps) = GlassesState.plan(GlassesState(now.mode, now.fps), want60, RokidDisplay.MODE_3D)
        var state: RokidDisplay.State? = now
        if (leave3d) state = display.setMode(RokidDisplay.MODE_2D) ?: state
        if (fps != null) state = display.setFps(fps) ?: state
        AppLog.i("glasses: AR from $now, now $state")
        return state
    }

    /** Puts the refresh rate back (the glasses stay in 2D) and forgets the saved state. */
    fun leave() {
        val saved = GlassesState.load(store)
        if (saved == null) {
            store.delete()
            return
        }
        val now = display.read()
        if (now != null && now.fps != saved.fps) {
            if (now.is3d) display.setMode(RokidDisplay.MODE_2D)
            display.setFps(saved.fps)
        }
        AppLog.i("glasses: left AR, back to ${saved.fps} Hz in 2D (were $saved)")
        store.delete()
    }

    /** A file left by a crash in AR (called at start when AR isn't opening). */
    fun restoreLeftover() {
        if (store.exists()) {
            AppLog.i("glasses: restoring after an AR session that didn't end")
            leave()
        }
    }

    companion object {
        /** Rokid's USB vendor id: the glasses' own USB (IMU and audio) is on the bus. */
        private const val ROKID_VENDOR = 0x04D2

        /** True when Rokid glasses are plugged into the Station (a USB device from Rokid). */
        fun plugged(context: Context): Boolean = try {
            val usb = context.getSystemService(UsbManager::class.java)
            usb?.deviceList?.values?.any { it.vendorId == ROKID_VENDOR } == true
        } catch (e: RuntimeException) {
            false
        }
    }
}
