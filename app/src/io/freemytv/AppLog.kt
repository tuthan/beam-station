package io.freemytv

import android.util.Log

/** The app's log (adb logcat -s FreeMyTV). Never logs keys. */
internal object AppLog {
    @JvmStatic
    fun w(message: String?) {
        Log.i("FreeMyTV", message ?: "null")
    }
}
