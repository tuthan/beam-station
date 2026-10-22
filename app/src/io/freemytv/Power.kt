package io.freemytv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Rokid Station: its battery (the box runs unplugged, behind a fuel gauge), the screensaver and sleep timeouts, and a
 * sleep timer. Only there: many TV boxes report a stand-in battery at 100 %, which would read as a real one. Rokid's
 * Settings has no timeouts; they're set through the TV's own ADB (`settings put`), as Android TV's Settings would, and
 * read back. The sleep timer is an alarm; when it rings, the TV's ADB presses the sleep key.
 */
internal object Power {
    class Battery(
        @JvmField val level: Int,          // percent
        @JvmField val plugged: Boolean,
    )

    class Timeouts {
        @JvmField var screensaver = 0      // seconds idle
        @JvmField var sleep = 0            // seconds idle; -1: never
        @JvmField var stayOnPlugged = false   // Android keeps the screen on while plugged in (stay_on_while_plugged_in)
    }

    /** Seconds idle before the screensaver starts: 30 s to 30 min. */
    @JvmField val SCREENSAVER = intArrayOf(30, 60, 120, 300, 600, 900, 1800)
    /** Seconds idle (the screensaver counts) before the Station sleeps: 5 min to 4 h; -1: never. */
    @JvmField val SLEEP = intArrayOf(300, 600, 900, 1800, 3600, 7200, 14400, -1)
    /** The sleep timer's steps, in minutes; 0: off. */
    @JvmField val TIMER = intArrayOf(0, 15, 30, 60, 90, 120)
    /** The sleep timer's alarm (to HomeReceiver, in the main process). */
    const val SLEEP_TIMER = "io.freemytv.SLEEP_TIMER"
    /** When the sleep timer goes off (System.currentTimeMillis), shared by the processes; empty: off. */
    private const val TIMER_FILE = "sleep-timer.txt"

    @JvmStatic
    fun available() = HomeMode.ROKID

    // MARK: - Battery

    /** The battery now (the sticky broadcast), or null where there's none to show. */
    @JvmStatic
    fun battery(c: Context): Battery? {
        if (!available()) return null
        return battery(c.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    /** What an ACTION_BATTERY_CHANGED says, or null when it has no battery in it. */
    @JvmStatic
    fun battery(i: Intent?): Battery? {
        if (i == null || !i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        return Battery((100f * level / scale).roundToInt(), i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    /** The battery's symbol: a bolt while plugged in, else how full it is. */
    @JvmStatic
    fun symbol(b: Battery) = when {
        b.plugged -> "battery_bolt"
        b.level >= 90 -> "battery_full"
        b.level >= 60 -> "battery_5"
        b.level >= 35 -> "battery_3"
        b.level >= 10 -> "battery_1"
        else -> "battery_alert"
    }

    // MARK: - Timeouts (blocking: never on the main thread)

    @JvmStatic
    @Throws(IOException::class)
    fun timeouts(c: Context): Timeouts = Adb.connect(c.applicationContext, 10_000, null).use { timeouts(it) }

    @JvmStatic
    @Throws(IOException::class)
    fun setScreensaver(c: Context, seconds: Int) = set(c, "system screen_off_timeout", seconds * 1000L)

    @JvmStatic
    @Throws(IOException::class)
    fun setSleep(c: Context, seconds: Int) = set(c, "secure sleep_timeout", if (seconds < 0) -1 else seconds * 1000L)

    private fun set(c: Context, setting: String, ms: Long): Timeouts =
        Adb.connect(c.applicationContext, 10_000, null).use { adb ->
            adb.shell("settings put $setting $ms")
            AppLog.w("power: $setting = $ms")
            timeouts(adb)
        }

    private fun timeouts(adb: Adb): Timeouts {
        val t = Timeouts()
        t.screensaver = seconds(adb.shell("settings get system screen_off_timeout"))
        t.sleep = seconds(adb.shell("settings get secure sleep_timeout"))
        t.stayOnPlugged = adb.shell("settings get global stay_on_while_plugged_in").trim() != "0"
        return t
    }

    /** Milliseconds as `settings get` prints them, in seconds; -1 for never (negative, or unset: "null"). */
    private fun seconds(ms: String): Int {
        val v = ms.trim().toLongOrNull() ?: return -1
        return if (v < 0) -1 else (v / 1000.0).roundToInt()
    }

    /** A timeout in seconds, as the settings show it: 30 s, 5 min, 2 h. */
    @JvmStatic
    fun seconds(s: Int): String = if (s < 60) s.toString() + (if (Ui.ru) " с" else " s") else duration((s / 60f).roundToInt())

    /** A length of time, as the settings show it: 30 min, 2 h, 1 h 30 min. */
    @JvmStatic
    fun duration(minutes: Int): String {
        val min = if (Ui.ru) " мин" else " min"
        val h = if (Ui.ru) " ч" else " h"
        if (minutes < 60) return "$minutes$min"
        return if (minutes % 60 == 0) "${minutes / 60}$h" else "${minutes / 60}$h ${minutes % 60}$min"
    }

    // MARK: - Sleep timer

    /** When the sleep timer goes off (System.currentTimeMillis), or 0 when it's off. */
    @JvmStatic
    fun timerEnd(c: Context): Long {
        val end = readEnd(c)
        return if (end > System.currentTimeMillis()) end else 0
    }

    /** Minutes left on the sleep timer at `end` (from timerEnd), rounded up; 0 when it's off. */
    @JvmStatic
    fun left(end: Long): Int {
        val ms = end - System.currentTimeMillis()
        return if (end == 0L || ms <= 0) 0 else ((ms + 59_999) / 60_000).toInt()
    }

    /** The timer step after (dir 1) or before (dir -1) what's left now: off, 15, 30, 60, 90, 120 min. */
    @JvmStatic
    fun nextTimer(left: Int, dir: Int): Int {
        var at = 0
        while (at < TIMER.size - 1 && TIMER[at] < left) at++
        if (left > TIMER[at]) at = TIMER.size - 1
        return TIMER[(at + dir + TIMER.size) % TIMER.size]
    }

    /** Sets the sleep timer to `minutes` from now; 0 turns it off. */
    @JvmStatic
    fun setTimer(c: Context, minutes: Int) {
        val app = c.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val ring = PendingIntent.getBroadcast(app, 0, Intent(SLEEP_TIMER).setClass(app, HomeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (minutes <= 0) {
            alarms.cancel(ring)
            writeEnd(app, 0)
            AppLog.w("sleep timer: off")
            return
        }
        val ms = minutes * 60_000L
        // Not a wakeup alarm: asleep already, there's nothing to do (timerRang sees it's late).
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ms, 30_000, ring)
        writeEnd(app, System.currentTimeMillis() + ms)
        AppLog.w("sleep timer: $minutes min")
    }

    /** The sleep timer rang: the Station goes to sleep. Rang late (it slept meanwhile and has just woken): nothing. */
    @JvmStatic
    fun timerRang(c: Context) {
        val end = readEnd(c)
        writeEnd(c, 0)
        if (end == 0L || System.currentTimeMillis() - end > 120_000) return
        try {
            Adb.connect(c.applicationContext, 10_000, null).use { adb ->
                adb.shell("input keyevent KEYCODE_SLEEP")
                AppLog.w("sleep timer: the Station goes to sleep")
            }
        } catch (e: IOException) {
            AppLog.w("sleep timer: " + e.message)
        }
    }

    private fun readEnd(c: Context): Long = try {
        val f = File(c.filesDir, TIMER_FILE)
        if (f.exists()) f.readText(Charsets.US_ASCII).trim().toLongOrNull() ?: 0 else 0
    } catch (e: IOException) {
        0
    }

    private fun writeEnd(c: Context, end: Long) {
        try {
            File(c.filesDir, TIMER_FILE).writeText(if (end == 0L) "" else end.toString(), Charsets.US_ASCII)
        } catch (e: IOException) {
            AppLog.w("sleep timer: " + e.message)
        }
    }
}
