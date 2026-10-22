package io.github.tuthan.stationar.core.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelLayoutTest {
    private fun items(prefix: String, n: Int) = (1..n).map { PanelLayout.Item("$prefix$it", "$prefix $it") }

    private fun panel() = PanelLayout(perRow = 3).apply {
        set(listOf("Layout" to items("l", 2), "Apps" to items("a", 7), "Empty" to emptyList(), "Leave" to items("x", 1)))
    }

    @Test
    fun sectionsWrapIntoRows() {
        val p = panel()
        assertEquals(listOf(2, 3, 3, 1, 1), p.rows.map { it.items.size })
        assertEquals(listOf("Layout", "Apps", null, null, "Leave"), p.rows.map { it.title })
    }

    @Test
    fun emptySectionsAreLeftOut() {
        assertTrue(panel().rows.none { it.title == "Empty" })
    }

    @Test
    fun movingStopsAtTheEdges() {
        val p = panel()
        assertFalse(p.move(0, -1))
        assertFalse(p.move(-1, 0))
        assertTrue(p.move(1, 0))
        assertFalse(p.move(1, 0))
        assertEquals("l2", p.focusedItem()?.id)
    }

    @Test
    fun goingDownKeepsTheColumnWhereItCan() {
        val p = panel()
        p.move(1, 0)
        p.move(0, 1)
        assertEquals("a2", p.focusedItem()?.id)
        p.move(1, 0)
        p.move(0, 1)
        p.move(0, 1)
        // The row below has one item: the column clamps to it.
        assertEquals("a7", p.focusedItem()?.id)
        p.move(0, 1)
        assertEquals("x1", p.focusedItem()?.id)
    }

    @Test
    fun theFocusSurvivesNewContents() {
        val p = panel()
        assertTrue(p.focus("a5"))
        p.set(listOf("Apps" to items("a", 7)))
        assertEquals("a5", p.focusedItem()?.id)
        p.set(listOf("Other" to items("o", 2)))
        assertEquals("o1", p.focusedItem()?.id)
    }

    @Test
    fun nothingToFocusInAnEmptyPanel() {
        val p = PanelLayout()
        p.set(emptyList())
        assertNull(p.focusedItem())
        assertFalse(p.move(0, 1))
    }
}
