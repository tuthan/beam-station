package io.github.tuthan.stationar

import android.content.Context
import io.github.tuthan.stationar.core.store.KvStore
import io.github.tuthan.stationar.core.store.Settings
import java.io.File

/** App-wide pieces: the one settings file, and names for screens. */
object StationAr {
    private var settings: Settings? = null

    @Synchronized
    fun settings(c: Context): Settings =
        settings ?: Settings(KvStore(File(c.applicationContext.filesDir, "settings.txt"))).also { settings = it }

    /** What the HUD and the panel call a screen's source. */
    fun screenLabel(source: String): String {
        val kind = source.substringBefore(':')
        val target = source.substringAfter(':', "")
        return when (kind) {
            "card" -> "Test card"
            "laptop" -> "Laptop ${(target.toIntOrNull() ?: 0) + 1}"
            "wide" -> "Laptop " + if (target == "0") "left" else "right"
            "app" -> appNames[target] ?: target.substringAfterLast('.')
            else -> source
        }
    }

    /** App labels by package, filled when the apps are listed. */
    val appNames = java.util.concurrent.ConcurrentHashMap<String, String>()
}
