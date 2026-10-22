package io.github.tuthan.beamstation

import android.util.Log

/** The app's log (adb logcat -s BeamStation). Never logs keys: a URL's api_key (TMDB) or apiKey (Watchmode) is cut. */
internal object AppLog {
    private val KEY = Regex("(?i)(api_?key=)[^&\\s\"']+")

    @JvmStatic
    fun w(message: String?) {
        val m = message ?: "null"
        Log.i("BeamStation", if (m.contains("key=", ignoreCase = true)) KEY.replace(m, "$1…") else m)
    }
}
