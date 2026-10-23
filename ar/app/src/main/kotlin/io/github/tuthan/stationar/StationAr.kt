package io.github.tuthan.stationar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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

    /** `pkg`'s name, as the app list shows it; looked up when the list hasn't been shown (an app opened from Station Home). */
    fun appName(c: Context, pkg: String): String = appNames.getOrPut(pkg) {
        try {
            c.packageManager.getApplicationInfo(pkg, 0).loadLabel(c.packageManager).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    /** App labels by package, filled when the apps are listed. */
    val appNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** `pkg`'s icon, square, for the panel's tiles and an app screen's card; made once. Null when it has none. */
    fun appIcon(c: Context, pkg: String): Bitmap? {
        appIcons[pkg]?.let { return it }
        val d = try {
            c.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            return null
        }
        val b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(b))
        appIcons[pkg] = b
        return b
    }

    private val appIcons = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private const val ICON_PX = 128
}
