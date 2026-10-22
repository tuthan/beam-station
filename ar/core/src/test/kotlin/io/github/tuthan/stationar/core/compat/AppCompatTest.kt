package io.github.tuthan.stationar.core.compat

import io.github.tuthan.stationar.core.scene.Layout
import io.github.tuthan.stationar.core.scene.Presets
import io.github.tuthan.stationar.core.scene.ScreenSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppCompatTest {
    @Test
    fun test1sResults() {
        assertEquals(AppCompat.Status.WORKS, AppCompat.of("org.videolan.vlc").status)
        assertEquals(AppCompat.Status.NO_VIDEO, AppCompat.of("com.google.android.youtube.tv").status)
        assertEquals(AppCompat.Status.PARTLY, AppCompat.of("com.liskovsoft.smarttubetv.beta").status)
        assertEquals(AppCompat.Status.DRM, AppCompat.of("com.netflix.ninja").status)
        assertEquals(AppCompat.Status.UNTESTED, AppCompat.of("com.example.whatever").status)
        val sorted = listOf("com.netflix.ninja", "com.example.x", "org.videolan.vlc", "com.google.android.youtube.tv")
            .sortedBy { AppCompat.rank(it) }
        assertEquals(listOf("org.videolan.vlc", "com.example.x", "com.google.android.youtube.tv", "com.netflix.ninja"), sorted)
    }

    @Test
    fun anAppTakesTheCardYouLookAtOrTheNextFreePlace() {
        val onCard = assertNotNull(Presets.placeApp(Presets.welcome(), "app:org.videolan.vlc", 5f))
        assertEquals(listOf("app:org.videolan.vlc"), onCard.screens.map { it.source })
        assertEquals(Presets.APP_WIDTH, onCard.screens[0].width)
        // Two laptop monitors ahead: the app goes to the first free place to the right of the gaze.
        val desk = assertNotNull(Presets.layout(Presets.DESK))
        val beside = assertNotNull(Presets.placeApp(desk, "app:org.videolan.vlc", 0f))
        val app = beside.screens.last()
        assertEquals("app:org.videolan.vlc", app.source)
        for (s in desk.screens) {
            assertTrue(kotlin.math.abs(app.yaw - s.yaw) >= (app.width + s.width) / 2, "overlaps $s")
        }
        // Already there: unchanged.
        assertEquals(beside, Presets.placeApp(beside, "app:org.videolan.vlc", 0f))
        val full = Layout(MutableList(Layout.MAX_SCREENS) { ScreenSpec("laptop:$it", it * 50f - 150f, 0f, 44f) })
        assertNull(Presets.placeApp(full, "app:x", 0f))
    }
}
