package io.freemytv

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Settings changed on the TV itself (the app's Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
internal object Prefs {
    /** Bumped by every change: screens compare it to know when to redraw. */
    @JvmField @Volatile var version = 0

    private var app: Context? = null

    @JvmStatic
    fun init(c: Context) {
        app = c.applicationContext
        Ui.applyLanguage()   // the language picked on the TV, now that it can be read
    }

    private fun sp(): SharedPreferences = app!!.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun put(key: String, value: String) {
        sp().edit().putString(key, value).apply()
        version++
    }

    private fun put(key: String, value: Boolean) {
        sp().edit().putBoolean(key, value).apply()
        version++
    }

    private fun string(key: String, default: String) = sp().getString(key, default) ?: default

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de" or "ru". */
    @JvmStatic
    fun language(): String {
        val v = (if (app == null) Config.LANGUAGE else string("language", Config.LANGUAGE)).trim().lowercase(Locale.ROOT)
        return when {
            v.startsWith("ru") -> "ru"
            v.startsWith("pt") -> "pt"
            v.startsWith("es") -> "es"
            v.startsWith("de") -> "de"
            v.startsWith("en") -> "en"
            else -> "auto"
        }
    }

    @JvmStatic
    fun setLanguage(v: String) {
        put("language", v)
        Ui.applyLanguage()
    }

    /** "auto", "C" or "F". */
    @JvmStatic
    fun temperature(): String {
        val v = string("temperature", Config.TEMPERATURE).trim().uppercase(Locale.ROOT)
        return if (v.startsWith("F")) "F" else if (v.startsWith("C")) "C" else "auto"
    }

    @JvmStatic
    fun setTemperature(v: String) = put("temperature", v)

    /**
     * "auto" (dark from sunset to sunrise), "light" or "dark". Both builds start dark: the projector for the dark room
     * it plays in, the glasses because in them black is see-through and white glares.
     */
    @JvmStatic
    fun theme(): String {
        val v = string("theme", "dark")
        return if (v == "light" || v == "dark") v else "auto"
    }

    @JvmStatic
    fun setTheme(v: String) = put("theme", v)

    // MARK: - City

    @JvmStatic
    fun city(): String = string("city", Config.CITY).trim()
    @JvmStatic
    fun latitude(): String = string("latitude", Config.LATITUDE)
    @JvmStatic
    fun longitude(): String = string("longitude", Config.LONGITUDE)

    @JvmStatic
    fun setCity(name: String, latitude: String, longitude: String) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply()
        version++
    }

    /** The home screen's soft click when moving and opening. */
    @JvmStatic
    fun sounds() = sp().getBoolean("sounds", true)
    @JvmStatic
    fun setSounds(on: Boolean) = put("sounds", on)

    // MARK: - Widgets and coins

    @JvmStatic
    fun weather() = sp().getBoolean("weather", true)
    @JvmStatic
    fun setWeather(on: Boolean) = put("weather", on)

    @JvmStatic
    fun quotes() = sp().getBoolean("quotes", Config.SHOW_CRYPTO)
    @JvmStatic
    fun setQuotes(on: Boolean) = put("quotes", on)

    @JvmStatic
    fun coin(i: Int) = sp().getBoolean("coin$i", true)
    @JvmStatic
    fun setCoin(i: Int, on: Boolean) = put("coin$i", on)

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    @JvmStatic
    fun shelf(): MutableList<String>? =
        sp().getString("shelf", null)?.split(",")?.filter { it.isNotEmpty() }?.toMutableList()

    @JvmStatic
    fun setShelf(packages: List<String>) = put("shelf", packages.joinToString(","))
}
