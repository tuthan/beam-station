package io.github.tuthan.stationar.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A small key-value file, written all or nothing: to a file alongside, synced to disk, then renamed over the real one,
 * so a power cut leaves either the old file or the new one. One `key=value` per line; newlines and backslashes in
 * values are escaped. Keys it doesn't know are kept as they are.
 *
 * Thread-safe; every [put] and [remove] writes the file at once (they're rare: settings, pairing, the glasses' state).
 */
class KvStore(private val file: File) {
    private val values = LinkedHashMap<String, String>()

    init {
        load()
    }

    @Synchronized
    fun get(key: String): String? = values[key]

    @Synchronized
    fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default

    @Synchronized
    fun getFloat(key: String, default: Float): Float = values[key]?.toFloatOrNull() ?: default

    @Synchronized
    fun getBoolean(key: String, default: Boolean): Boolean = when (values[key]) {
        "true" -> true
        "false" -> false
        else -> default
    }

    @Synchronized
    fun keys(): List<String> = values.keys.toList()

    @Synchronized
    @Throws(IOException::class)
    fun put(key: String, value: String) {
        require(isKey(key)) { "bad key: $key" }
        values[key] = value
        save()
    }

    @Synchronized
    @Throws(IOException::class)
    fun putAll(entries: Map<String, String>) {
        for (k in entries.keys) require(isKey(k)) { "bad key: $k" }
        values.putAll(entries)
        save()
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(key: String) {
        if (values.remove(key) != null) save()
    }

    /** True when the file exists on disk. */
    fun exists(): Boolean = file.exists()

    /** Deletes the file and forgets everything. */
    @Synchronized
    fun delete() {
        values.clear()
        file.delete()
    }

    private fun load() {
        if (!file.exists()) return
        for (line in file.readLines(Charsets.UTF_8)) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            if (isKey(key)) values[key] = unescape(line.substring(eq + 1))
        }
    }

    private fun save() {
        val text = buildString {
            for ((k, v) in values) append(k).append('=').append(escape(v)).append('\n')
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not save ${file.name}")
    }

    companion object {
        private val KEY = Regex("[A-Za-z0-9_.:-]{1,64}")

        fun isKey(key: String) = KEY.matches(key)

        fun escape(v: String): String = buildString {
            for (c in v) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }

        fun unescape(v: String): String = buildString {
            var i = 0
            while (i < v.length) {
                val c = v[i]
                if (c == '\\' && i + 1 < v.length) {
                    when (v[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(v[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
