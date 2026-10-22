package io.github.tuthan.stationar.core.scene

/**
 * One screen in the room: where it is (yaw positive to the right, pitch positive upwards, in degrees from where you
 * re-centred), how wide it looks (degrees), its shape, and what it shows.
 *
 * Sources: [CARD] (the test card), `laptop:<n>` (laptop monitor n), `wide:<half>` (half 0 or 1 of one wide laptop
 * stream), `app:<package>` (an Android app on a virtual display).
 */
class ScreenSpec(
    var source: String,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var width: Float = 40f,
    var aspect: Float = 16f / 9f,
    var curved: Boolean = false,
) {
    /** The angular height, in degrees (width / aspect; close enough at these sizes). */
    val height: Float get() = width / aspect

    fun copy() = ScreenSpec(source, yaw, pitch, width, aspect, curved)

    /** The kind of source: "card", "laptop", "wide" or "app". */
    val kind: String get() = source.substringBefore(':')

    /** What follows the kind: the monitor number, the half, or the package. */
    val target: String get() = source.substringAfter(':', "")

    override fun equals(other: Any?) = other is ScreenSpec && other.source == source && other.yaw == yaw &&
        other.pitch == pitch && other.width == width && other.aspect == aspect && other.curved == curved

    override fun hashCode() = source.hashCode() * 31 + yaw.hashCode()

    override fun toString() = encode()

    /** One line: `source,yaw,pitch,width,aspect,flat|curved`. Sources never contain a comma or a space. */
    fun encode() = "$source,$yaw,$pitch,$width,$aspect,${if (curved) "curved" else "flat"}"

    companion object {
        const val CARD = "card"
        private val SOURCE = Regex("(card|laptop:[0-9]|wide:[01]|app:[A-Za-z0-9_.]{1,200})")

        fun isSource(s: String) = SOURCE.matches(s)

        /** The screen in [encode]'s form, or null when it isn't one (or its numbers are out of range). */
        fun decode(line: String): ScreenSpec? {
            val p = line.split(',')
            if (p.size != 6 || !isSource(p[0])) return null
            val yaw = p[1].toFloatOrNull() ?: return null
            val pitch = p[2].toFloatOrNull() ?: return null
            val width = p[3].toFloatOrNull() ?: return null
            val aspect = p[4].toFloatOrNull() ?: return null
            if (yaw !in -180f..180f || pitch !in -80f..80f || width !in 5f..150f || aspect !in 0.25f..8f) return null
            val curved = when (p[5]) {
                "curved" -> true
                "flat" -> false
                else -> return null
            }
            return ScreenSpec(p[0], yaw, pitch, width, aspect, curved)
        }
    }
}
