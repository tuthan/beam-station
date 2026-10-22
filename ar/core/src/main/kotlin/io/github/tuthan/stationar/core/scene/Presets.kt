package io.github.tuthan.stationar.core.scene

/**
 * The layouts the AR menu offers. Angular sizes assume the Rokid Max: about 44° across for 1920 px, so a laptop
 * monitor at 1:1 (every pixel one pixel on the glasses, the sharpest) is [ONE_TO_ONE] wide.
 */
object Presets {
    const val CINEMA = "cinema"
    const val DESK = "desk"
    const val DESK_APP = "desk-app"
    const val LAST = "last"

    /** Degrees for 1920 px at 1:1 on the Max (1920 px over a 44.2° wide field). */
    const val ONE_TO_ONE = 44.2f

    val NAMES = listOf(CINEMA, DESK, DESK_APP)

    fun label(name: String) = when (name) {
        CINEMA -> "Cinema"
        DESK -> "Desk"
        DESK_APP -> "Desk + app"
        else -> "Custom"
    }

    /**
     * The preset's screens. [first] is what the main screen shows (the test card until a laptop or an app is there);
     * [app] the app screen's source in Desk + app.
     */
    fun layout(name: String, first: String = ScreenSpec.CARD, app: String = ScreenSpec.CARD): Layout? = when (name) {
        // One big screen straight ahead, curved like a cinema's.
        CINEMA -> Layout(mutableListOf(ScreenSpec(first, 0f, 0f, 60f, 16f / 9f, curved = true)))
        // Two laptop monitors side by side at 1:1, meeting straight ahead.
        DESK -> Layout(
            mutableListOf(
                ScreenSpec("laptop:0", -ONE_TO_ONE / 2 - 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec("laptop:1", ONE_TO_ONE / 2 + 0.5f, 0f, ONE_TO_ONE),
            ),
        )
        // The two monitors, and an app screen above them.
        DESK_APP -> Layout(
            mutableListOf(
                ScreenSpec("laptop:0", -ONE_TO_ONE / 2 - 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec("laptop:1", ONE_TO_ONE / 2 + 0.5f, 0f, ONE_TO_ONE),
                ScreenSpec(app, 0f, 23.5f, 36f),
            ),
        )
        else -> null
    }

    /** The default when nothing was saved: the test card straight ahead, 40° wide. */
    fun welcome() = Layout(mutableListOf(ScreenSpec(ScreenSpec.CARD, 0f, 0f, 40f)))
}
