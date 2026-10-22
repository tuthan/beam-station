package io.github.tuthan.stationar.keys

import android.content.Context
import android.provider.Settings
import io.github.tuthan.stationar.adb.AdbClient

/**
 * Puts [KeyFilterService] into the Station's `enabled_accessibility_services` while AR has app screens, and takes it
 * out again, over the app's own ADB (an app can't write secure settings). Every other entry (Rokid's InputAssistService
 * and MouseAssistService) stays as it is, in its place; `accessibility_enabled` isn't touched.
 */
object KeyFilterSetting {
    const val KEY = "enabled_accessibility_services"
    private const val FULL = "io.github.tuthan.stationar/io.github.tuthan.stationar.keys.KeyFilterService"
    private const val SHORT = "io.github.tuthan.stationar/.keys.KeyFilterService"

    private fun ours(entry: String) = entry == FULL || entry == SHORT

    /** True when the setting holds our entry (readable without ADB). */
    fun isOn(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, KEY)?.split(':')?.any { ours(it) } ?: false

    /** Adds (`on`) or removes our entry, leaving the rest as it was. */
    fun set(adb: AdbClient, on: Boolean) {
        val next = next(adb.shell("settings get secure $KEY").trim(), on) ?: return
        if (next.isEmpty()) adb.shell("settings delete secure $KEY")
        else adb.shell("settings put secure $KEY '${next.joinToString(":")}'")
    }

    /**
     * The setting's entries with ours added (`on`) or taken out, every other one as it was; null when nothing changes.
     * `now` is what `settings get` printed. Also used by the shell helper, which takes ours out if the app died.
     */
    fun next(now: String, on: Boolean): List<String>? {
        val list = if (now.isEmpty() || now == "null") emptyList() else now.split(':').filter { it.isNotEmpty() }
        val others = list.filter { !ours(it) }
        val next = if (on) others + FULL else others
        if (next == list) return null
        for (e in next) require(Regex("[A-Za-z0-9_.$/]+").matches(e)) { "unexpected entry in $KEY" }
        return next
    }
}
