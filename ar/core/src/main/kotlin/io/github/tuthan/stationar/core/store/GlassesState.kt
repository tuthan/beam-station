package io.github.tuthan.stationar.core.store

/**
 * What the glasses were set to before AR changed them, saved before the change so that leaving AR, or the next start
 * after a crash, can put the refresh rate back. The mode isn't restored: leaving AR leaves the glasses in 2D, which
 * Station Home puts back on every resume anyway (one HDMI retrain instead of two).
 */
class GlassesState(val mode: Int, val fps: Int) {
    fun save(store: KvStore) {
        store.putAll(mapOf(MODE to mode.toString(), FPS to fps.toString()))
    }

    override fun equals(other: Any?) = other is GlassesState && other.mode == mode && other.fps == fps

    override fun hashCode() = mode * 31 + fps

    override fun toString() = "mode $mode, $fps Hz"

    companion object {
        private const val MODE = "mode"
        private const val FPS = "fps"

        /** The saved state, or null when nothing (or nothing whole) was saved. */
        fun load(store: KvStore): GlassesState? {
            val mode = store.get(MODE)?.toIntOrNull() ?: return null
            val fps = store.get(FPS)?.toIntOrNull() ?: return null
            return GlassesState(mode, fps)
        }

        /**
         * What to set on entering AR: the refresh rate to use (60 when [want60] and the glasses run faster), and
         * whether to leave 3D. Null fields mean "leave as it is".
         */
        fun plan(now: GlassesState, want60: Boolean, mode3d: Int): Pair<Boolean, Int?> =
            Pair(now.mode == mode3d, if (want60 && now.fps > 60) 60 else null)
    }
}
