package io.github.tuthan.stationar.core.store

import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.scene.Layout
import io.github.tuthan.stationar.core.scene.Presets

/** Station AR's settings, typed and kept in range, on top of a [KvStore]. */
class Settings(private val store: KvStore) {
    /** Run the glasses at 60 Hz in AR (half the GPU work of 120). */
    var refresh60: Boolean
        get() = store.getBoolean("refresh60", true)
        set(v) = store.put("refresh60", v.toString())

    /** Screen size, 0.8 to 1.2 (scales every screen's angle). */
    var size: Float
        get() = store.getFloat("size", 1f).coerceIn(MIN_SIZE, MAX_SIZE)
        set(v) = store.put("size", v.coerceIn(MIN_SIZE, MAX_SIZE).toString())

    /** How far ahead the head pose is predicted, 0 to 60 ms. */
    var predictMs: Int
        get() = store.getInt("predictMs", 30).coerceIn(0, MAX_PREDICT_MS)
        set(v) = store.put("predictMs", v.coerceIn(0, MAX_PREDICT_MS).toString())

    /** The head's axes in the glasses' sensor frame, from the in-app calibration; null until one is done. */
    var axes: Quat?
        get() {
            val p = store.get("axes")?.split(',') ?: return null
            if (p.size != 4) return null
            val v = p.map { it.toFloatOrNull() ?: return null }
            return Quat(v[0], v[1], v[2], v[3]).normalize()
        }
        set(q) {
            if (q == null) store.remove("axes") else store.put("axes", "${q.w},${q.x},${q.y},${q.z}")
        }

    /** The layout AR left last; null when none was saved. */
    var lastLayout: Layout?
        get() = Layout.decode(store.get("layout"))
        set(l) {
            if (l == null) store.remove("layout") else store.put("layout", l.encode())
        }

    /** The layout AR opens in: a preset's name, or [Presets.LAST]. */
    var startPreset: String
        get() = store.get("startPreset")?.takeIf { it in Presets.NAMES || it == Presets.LAST } ?: Presets.LAST
        set(v) = store.put("startPreset", if (v in Presets.NAMES) v else Presets.LAST)

    /** The laptop receiver's TCP port. */
    var port: Int
        get() = store.getInt("port", DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        set(v) = store.put("port", (if (v in 1024..65535) v else DEFAULT_PORT).toString())

    companion object {
        const val MIN_SIZE = 0.8f
        const val MAX_SIZE = 1.2f
        const val MAX_PREDICT_MS = 60
        const val DEFAULT_PORT = 47800
    }
}
