package io.github.tuthan.stationar.core.scene

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneTest {
    private val two = listOf(ScreenSpec("laptop:0", -22.6f, 0f, 44.2f), ScreenSpec("laptop:1", 22.6f, 0f, 44.2f))

    @Test
    fun screenRoundTrip() {
        val s = ScreenSpec("app:org.jellyfin.androidtv", -12.5f, 3f, 40f, 16f / 9f, curved = true)
        assertEquals(s, ScreenSpec.decode(s.encode()))
    }

    @Test
    fun screensThatDontParse() {
        assertNull(ScreenSpec.decode("app:bad package,0,0,40,1.7,flat"))
        assertNull(ScreenSpec.decode("tv,0,0,40,1.7,flat"))
        assertNull(ScreenSpec.decode("card,0,0,400,1.7,flat"))
        assertNull(ScreenSpec.decode("card,0,0,40,1.7,round"))
        assertNull(ScreenSpec.decode("card,0,0,40"))
    }

    @Test
    fun layoutRoundTripDropsBadScreens() {
        val l = Layout(two.mapTo(ArrayList()) { it.copy() })
        assertEquals(l, Layout.decode(l.encode()))
        val withJunk = l.encode() + ";nonsense"
        assertEquals(2, assertNotNull(Layout.decode(withJunk)).screens.size)
        assertNull(Layout.decode(""))
        assertNull(Layout.decode("nonsense"))
    }

    @Test
    fun layoutIsCapped() {
        val many = (0 until 10).joinToString(";") { "card,${it * 10},0,20,1.7,flat" }
        assertEquals(Layout.MAX_SCREENS, assertNotNull(Layout.decode(many)).screens.size)
    }

    @Test
    fun theNearestScreenWins() {
        val f = Focus()
        assertEquals(0, f.hit(two, -10f, 0f))
        assertEquals(1, f.hit(two, 10f, 0f))
        assertEquals(-1, f.hit(two, 0f, 40f))
        assertEquals(-1, f.hit(two, 90f, 0f))
    }

    @Test
    fun theFirstFocusIsImmediate() {
        val f = Focus()
        assertTrue(f.update(two, -10f, 0f, 0))
        assertEquals(0, f.focused)
    }

    @Test
    fun switchingWaitsForTheDwell() {
        val f = Focus(dwellMs = 300)
        f.update(two, -10f, 0f, 0)
        assertFalse(f.update(two, 10f, 0f, 1000))
        assertFalse(f.update(two, 10f, 0f, 1200))
        assertEquals(0, f.focused)
        assertTrue(f.update(two, 10f, 0f, 1300))
        assertEquals(1, f.focused)
    }

    @Test
    fun aGlanceBackRestartsTheDwell() {
        val f = Focus(dwellMs = 300)
        f.update(two, -10f, 0f, 0)
        f.update(two, 10f, 0f, 1000)
        f.update(two, -10f, 0f, 1100)
        f.update(two, 10f, 0f, 1200)
        assertFalse(f.update(two, 10f, 0f, 1400))
        assertTrue(f.update(two, 10f, 0f, 1500))
    }

    @Test
    fun lookingAtNothingKeepsTheFocus() {
        val f = Focus()
        f.update(two, 10f, 0f, 0)
        assertFalse(f.update(two, 0f, 60f, 5000))
        assertEquals(1, f.focused)
    }

    @Test
    fun hysteresisAtTheBoundary() {
        // The screens meet at 0°; jitter across it never lasts the dwell, so the focus stays.
        val f = Focus(dwellMs = 300)
        f.update(two, -5f, 0f, 0)
        var t = 0L
        repeat(20) {
            t += 100
            f.update(two, if (it % 2 == 0) 0.5f else -0.5f, 0f, t)
        }
        assertEquals(0, f.focused)
    }

    @Test
    fun anglesWrap() {
        assertEquals(-10f, Focus.angle(350f), 1e-4f)
        assertEquals(10f, Focus.angle(-350f), 1e-4f)
        val behind = listOf(ScreenSpec("card", 179f, 0f, 20f))
        assertEquals(0, Focus().hit(behind, -179f, 0f))
    }

    @Test
    fun presetsDontOverlapAndStayInReach() {
        for (name in Presets.NAMES) {
            val l = assertNotNull(Presets.layout(name, app = "app:org.videolan.vlc"))
            for (s in l.screens) {
                assertTrue(abs(s.yaw) + s.width / 2 <= 70f, "$name: $s too far sideways")
                assertTrue(abs(s.pitch) + s.height / 2 <= 35f, "$name: $s too far up or down")
                assertNotNull(ScreenSpec.decode(s.encode()))
            }
            for (i in l.screens.indices) for (j in i + 1 until l.screens.size) {
                val a = l.screens[i]
                val b = l.screens[j]
                val apartYaw = abs(a.yaw - b.yaw) >= (a.width + b.width) / 2
                val apartPitch = abs(a.pitch - b.pitch) >= (a.height + b.height) / 2
                assertTrue(apartYaw || apartPitch, "$name: $a overlaps $b")
            }
        }
        assertNull(Presets.layout("nope"))
    }
}
