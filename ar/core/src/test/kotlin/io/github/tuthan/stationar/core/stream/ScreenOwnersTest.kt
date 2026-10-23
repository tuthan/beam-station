package io.github.tuthan.stationar.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenOwnersTest {
    private val sources = listOf("laptop:0", "laptop:1", "laptop:2")

    @Test
    fun twoLaptopsDontShareAScreen() {
        val o = ScreenOwners(sources)
        val never = { _: String -> false }
        assertEquals("laptop:0", o.assign("hvo-omarchy", "0", never))
        // The second laptop's screen 0 takes the next free source.
        assertEquals("laptop:1", o.assign("laptop2", "0", never))
        // Reconnects get their own back.
        assertEquals("laptop:0", o.assign("hvo-omarchy", "0", never))
        assertEquals("laptop:1", o.assign("laptop2", "0", never))
        // The first laptop's added monitor (its screen 1): laptop:1 is taken, so laptop:2.
        assertEquals("laptop:2", o.assign("hvo-omarchy", "1", never))
        assertTrue(o.added("laptop:2"))
        assertFalse(o.added("laptop:1"))
        assertEquals(setOf("laptop:0", "laptop:2"), o.of("hvo-omarchy"))
        assertEquals("laptop2", o.laptop("laptop:1"))
        // All taken.
        assertNull(o.assign("laptop2", "1", never))
        // Let go on the laptop: free for anyone.
        o.release("laptop:2")
        assertEquals("laptop:2", o.assign("laptop2", "1", never))
    }

    @Test
    fun aGoneLaptopsSourceCanBeReused() {
        val o = ScreenOwners(sources)
        val never = { _: String -> false }
        for ((laptop, screen) in listOf("a" to "0", "b" to "0", "c" to "0")) o.assign(laptop, screen, never)
        assertNull(o.assign("d", "0", never))
        // b's laptop has gone: d takes its source, and b gets another when it comes back.
        assertEquals("laptop:1", o.assign("d", "0") { it == "laptop:1" })
        assertEquals("d", o.laptop("laptop:1"))
        assertNull(o.assign("b", "0", never))
    }

    @Test
    fun aWideStreamKeepsItsOwnSource() {
        val o = ScreenOwners(sources)
        assertEquals("wide", o.assign("a", "wide") { false })
        assertFalse(o.added("wide"))
        assertEquals("laptop:0", o.assign("b", "0") { false })
    }
}
