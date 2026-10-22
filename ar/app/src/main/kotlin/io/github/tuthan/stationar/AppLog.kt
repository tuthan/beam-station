package io.github.tuthan.stationar

import android.util.Log

/** Station AR's log: `adb logcat -s StationAR`. Never logs tokens, PINs or keys. */
object AppLog {
    const val TAG = "StationAR"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, e: Throwable? = null) {
        if (e == null) Log.w(TAG, message) else Log.w(TAG, "$message: $e")
    }
}
