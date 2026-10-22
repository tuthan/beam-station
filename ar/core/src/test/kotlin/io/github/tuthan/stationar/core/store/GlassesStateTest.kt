package io.github.tuthan.stationar.core.store

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlassesStateTest {
    private val mode3d = 1

    @Test
    fun theRestoreFileRoundTrips() {
        val f = Files.createTempDirectory("glasses").toFile().resolve("glasses-restore.txt")
        GlassesState(1, 120).save(KvStore(f))
        assertEquals(GlassesState(1, 120), GlassesState.load(KvStore(f)))
    }

    @Test
    fun aHalfWrittenFileIsNothing() {
        val f = Files.createTempDirectory("glasses").toFile().resolve("glasses-restore.txt")
        f.writeText("mode=1\n")
        assertNull(GlassesState.load(KvStore(f)))
    }

    @Test
    fun from3dAt120EnteringGives2dAt60() {
        assertEquals(Pair(true, 60), GlassesState.plan(GlassesState(mode3d, 120), want60 = true, mode3d = mode3d))
    }

    @Test
    fun keeps120WhenAsked() {
        assertEquals(Pair(false, null), GlassesState.plan(GlassesState(3, 120), want60 = false, mode3d = mode3d))
    }

    @Test
    fun nothingToDoAt2dAnd60() {
        assertEquals(Pair(false, null), GlassesState.plan(GlassesState(0, 60), want60 = true, mode3d = mode3d))
    }
}
