package io.github.tuthan.beamstation

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Safety net for the :home process (home screen and screensaver): each crash is recorded, and on the 3rd within 5 min
 * the home screen goes back to the TV's original one (requested from the main process) and the app leaves a notice.
 * File: files/home-crashes.txt.
 */
internal object Watchdog {
    private const val WINDOW = 5 * 60_000L
    private const val LIMIT = 3
    private var installed = false

    @JvmStatic
    @Synchronized
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (record(app, System.currentTimeMillis()) >= LIMIT) HomeMode.requestRevertHome(app)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Our home screen crashed too often just now: don't retry (avoids a crash loop while the original comes back). */
    @JvmStatic
    fun crashedTooOften(c: Context) = recent(c, System.currentTimeMillis()).size >= LIMIT

    /** After our mode is turned back on, the count starts over. */
    @JvmStatic
    fun clear(c: Context) {
        file(c).delete()
    }

    private fun record(c: Context, now: Long): Int {
        val list = recent(c, now)
        list.add(now)
        file(c).writeText(list.joinToString("") { "$it\n" })
        return list.size
    }

    private fun recent(c: Context, now: Long): MutableList<Long> {
        val list = ArrayList<Long>()
        val f = file(c)
        if (!f.exists()) return list
        try {
            f.inputStream().use { input ->
                val data = ByteArray(minOf(f.length(), 4096L).toInt())
                val read = input.read(data)
                if (read <= 0) return list
                for (line in String(data, 0, read, Charsets.UTF_8).split("\n")) {
                    val at = line.trim().toLongOrNull() ?: continue
                    if (now - at < WINDOW && at <= now) list.add(at)
                }
            }
        } catch (ignored: IOException) {
        }
        return list
    }

    private fun file(c: Context) = File(c.filesDir, "home-crashes.txt")
}
