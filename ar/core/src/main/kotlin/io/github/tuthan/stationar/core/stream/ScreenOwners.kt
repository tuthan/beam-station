package io.github.tuthan.stationar.core.stream

/**
 * Whose screen each laptop source (`laptop:0` to `laptop:2`, and `wide`) shows, as (laptop, the laptop's own screen
 * number), while AR is open. Every sender numbers its monitors from 0: a second laptop's first one takes the next
 * free source instead of fighting the first laptop for `laptop:0`, and a laptop that reconnects gets its own back.
 *
 * Not thread-safe (AR's main thread).
 */
class ScreenOwners(private val laptopSources: List<String>) {
    private val owners = HashMap<String, Pair<String, String>>()

    /**
     * The source `laptop`'s screen `screen` shows: the one it had; else its own number if no laptop has that; else the
     * first free one; else one `reusable` says may be taken (its laptop has gone). Null when there's none.
     */
    fun assign(laptop: String, screen: String, reusable: (String) -> Boolean): String? {
        val key = laptop to screen
        owners.entries.firstOrNull { it.value == key }?.let { return it.key }
        val source = if (screen == "wide") "wide" else (listOf("laptop:$screen") + laptopSources).firstOrNull { it !in owners }
            ?: laptopSources.firstOrNull(reusable)
            ?: return null
        owners[source] = key
        return source
    }

    /** The laptop whose screen `source` shows, or null. */
    fun laptop(source: String): String? = owners[source]?.first

    /** The laptop's own number for the screen `source` shows ("0", "1", "wide"), or null. */
    fun screen(source: String): String? = owners[source]?.second

    /** The sources showing `laptop`'s screens. */
    fun of(laptop: String): Set<String> = owners.filterValues { it.first == laptop }.keys

    /** A monitor the laptop added after its first: put away, it's let go on the laptop (its monitor removed). */
    fun added(source: String): Boolean = screen(source).let { it != null && it != "0" && it != "wide" }

    /** `source` is free again (its laptop let that monitor go). */
    fun release(source: String) {
        owners.remove(source)
    }

    /** Every source with an owner, and the owner. */
    val all: Map<String, Pair<String, String>> get() = owners
}
