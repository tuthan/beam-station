package io.github.tuthan.stationar

import android.content.Context
import io.github.tuthan.stationar.core.store.KvStore
import io.github.tuthan.stationar.core.store.Settings
import io.github.tuthan.stationar.core.stream.Pairing
import java.io.File

/** App-wide pieces: the settings file, the paired laptops, and names for screens. */
object StationAr {
    private var settings: Settings? = null
    private var pairing: Pairing? = null

    @Synchronized
    fun settings(c: Context): Settings =
        settings ?: Settings(KvStore(File(c.applicationContext.filesDir, "settings.txt"))).also { settings = it }

    /** The laptops paired with this Station (their tokens' hashes, in laptops.txt). */
    @Synchronized
    fun pairing(c: Context): Pairing =
        pairing ?: Pairing(KvStore(File(c.applicationContext.filesDir, "laptops.txt"))).also { pairing = it }

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
