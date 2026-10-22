package io.freemytv

import android.app.Application

/** Gives Prefs a context in every process. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
