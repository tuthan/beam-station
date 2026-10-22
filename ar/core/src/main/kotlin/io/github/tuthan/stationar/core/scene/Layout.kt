package io.github.tuthan.stationar.core.scene

/** The screens in the room, in drawing order (later ones are drawn over earlier ones). */
class Layout(val screens: MutableList<ScreenSpec> = ArrayList()) {
    fun copy() = Layout(screens.mapTo(ArrayList()) { it.copy() })

    fun indexOf(source: String): Int = screens.indexOfFirst { it.source == source }

    /** `;`-separated [ScreenSpec.encode] lines, for the settings file. */
    fun encode(): String = screens.joinToString(";") { it.encode() }

    override fun equals(other: Any?) = other is Layout && other.screens == screens

    override fun hashCode() = screens.hashCode()

    override fun toString() = encode()

    companion object {
        /** At most this many screens; more would cost more than the Station's GPU and RAM have. */
        const val MAX_SCREENS = 6

        /** The layout in [encode]'s form; screens that don't parse are dropped. Null when nothing is left. */
        fun decode(text: String?): Layout? {
            if (text.isNullOrBlank()) return null
            val screens = text.split(';').mapNotNull { ScreenSpec.decode(it) }.take(MAX_SCREENS)
            return if (screens.isEmpty()) null else Layout(screens.toMutableList())
        }
    }
}
