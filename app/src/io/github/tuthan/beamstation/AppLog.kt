package io.github.tuthan.beamstation

import android.util.Log

/** The app's log (adb logcat -s BeamStation). Never logs keys. */
internal object AppLog {
    @JvmStatic
    fun w(message: String?) {
        Log.i("BeamStation", message ?: "null")
    }
}
