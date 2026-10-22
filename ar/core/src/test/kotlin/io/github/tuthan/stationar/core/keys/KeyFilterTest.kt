package io.github.tuthan.stationar.core.keys

import io.github.tuthan.stationar.core.keys.KeyFilter.Companion.BACK
import io.github.tuthan.stationar.core.keys.KeyFilter.Companion.HOME
import io.github.tuthan.stationar.core.keys.KeyFilter.Companion.MENU
import io.github.tuthan.stationar.core.keys.KeyFilter.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyFilterTest {
    private val dpadLeft = 21

    @Test
    fun everythingPassesWhenNoAppHasTheRemote() {
        val f = KeyFilter()
        for (code in listOf(OK, BACK, MENU, dpadLeft)) {
            assertFalse(f.onKey(code, true, 0, 0, false).consume)
            assertFalse(f.onKey(code, false, 0, 10, false).consume)
        }
    }

    @Test
    fun theDpadAndItsRepeatsGoToTheApp() {
        val f = KeyFilter().apply { live = true }
        assertFalse(f.onKey(dpadLeft, true, 0, 0, false).consume)
        assertFalse(f.onKey(dpadLeft, true, 1, 400, false).consume)
        assertFalse(f.onKey(dpadLeft, false, 0, 450, false).consume)
    }

    @Test
    fun menuOpensTheArPanel() {
        val f = KeyFilter().apply { live = true }
        val down = f.onKey(MENU, true, 0, 0, false)
        assertTrue(down.consume)
        assertEquals(KeyFilter.Action.AR_MENU, down.action)
        val up = f.onKey(MENU, false, 0, 80, false)
        assertTrue(up.consume)
        assertNull(up.action)
    }

    @Test
    fun aShortOkIsSentOnAsAWholePress() {
        val f = KeyFilter().apply { live = true }
        val down = f.onKey(OK, true, 0, 1000, false)
        assertTrue(down.consume)
        assertEquals(1500, down.checkAt)
        assertNull(f.onTimeout(1499).action)
        val up = f.onKey(OK, false, 0, 1120, false)
        assertTrue(up.consume)
        assertEquals(OK, up.resend)
        // The check after the release finds nothing held.
        assertNull(f.onTimeout(1500).action)
        // The resent press comes back through the filter, marked as ours: it passes.
        assertFalse(f.onKey(OK, true, 0, 1130, true).consume)
        assertFalse(f.onKey(OK, false, 0, 1131, true).consume)
    }

    @Test
    fun heldOkRecentresAndHeldBackOpensTheScreenMenu() {
        val f = KeyFilter().apply { live = true }
        f.onKey(OK, true, 0, 0, false)
        assertEquals(KeyFilter.Action.RECENTRE, f.onTimeout(500).action)
        assertNull(f.onTimeout(600).action)   // once
        val up = f.onKey(OK, false, 0, 900, false)
        assertTrue(up.consume)
        assertEquals(0, up.resend)
        f.onKey(BACK, true, 0, 2000, false)
        assertEquals(KeyFilter.Action.SCREEN_MENU, f.onTimeout(2500).action)
        assertEquals(0, f.onKey(BACK, false, 0, 2600, false).resend)
    }

    @Test
    fun aDeviceThatRepeatsKeysItselfAlsoGetsTheLongPress() {
        val f = KeyFilter().apply { live = true }
        f.onKey(BACK, true, 0, 0, false)
        assertNull(f.onKey(BACK, true, 1, 400, false).action)
        assertEquals(KeyFilter.Action.SCREEN_MENU, f.onKey(BACK, true, 2, 520, false).action)
        assertNull(f.onKey(BACK, true, 3, 560, false).action)
        assertNull(f.onTimeout(500).action)
        assertEquals(0, f.onKey(BACK, false, 0, 600, false).resend)
    }

    @Test
    fun aKeyPressedBeforeTheAppHadTheRemoteEndsInTheApp() {
        val f = KeyFilter()
        f.onKey(OK, true, 0, 0, false)
        f.live = true
        assertFalse(f.onKey(OK, false, 0, 100, false).consume)
    }

    @Test
    fun losingTheRemoteMidPressDropsTheHeldKey() {
        val f = KeyFilter().apply { live = true }
        f.onKey(OK, true, 0, 0, false)
        f.live = false
        f.live = true
        assertNull(f.onTimeout(600).action)
        assertFalse(f.onKey(OK, false, 0, 700, false).consume)
    }

    @Test
    fun homeLeavesArWhenAnAppHasTheRemote() {
        val f = KeyFilter().apply { live = true }
        val down = f.onKey(HOME, true, 0, 0, false)
        assertTrue(down.consume)
        assertNull(down.action)
        val up = f.onKey(HOME, false, 0, 80, false)
        assertTrue(up.consume)
        assertEquals(KeyFilter.Action.LEAVE, up.action)
        assertFalse(KeyFilter().onKey(HOME, true, 0, 0, false).consume)
    }

    @Test
    fun backPressedWhileOkIsHeldGoesToTheAppAndOkStillCounts() {
        val f = KeyFilter().apply { live = true }
        assertTrue(f.onKey(OK, true, 0, 0, false).consume)
        assertFalse(f.onKey(BACK, true, 0, 100, false).consume)
        assertFalse(f.onKey(BACK, false, 0, 150, false).consume)
        assertEquals(OK, f.onKey(OK, false, 0, 200, false).resend)
    }
}
