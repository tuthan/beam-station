package io.github.tuthan.stationar.core.store

import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.scene.Presets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {
    private fun file() = Files.createTempDirectory("settings").toFile().resolve("settings.txt")

    @Test
    fun defaults() {
        val s = Settings(KvStore(file()))
        assertTrue(s.refresh60)
        assertEquals(1f, s.size)
        assertEquals(30, s.predictMs)
        assertNull(s.axes)
        assertNull(s.lastLayout)
        assertEquals(Presets.LAST, s.startPreset)
        assertEquals(47800, s.port)
    }

    @Test
    fun roundTrip() {
        val f = file()
        Settings(KvStore(f)).apply {
            refresh60 = false
            size = 1.1f
            predictMs = 20
            axes = Quat().setAxisAngle(1f, 0f, 0f, 1.5707964f)
            lastLayout = Presets.layout(Presets.DESK)
            startPreset = Presets.CINEMA
            port = 47900
        }
        val s = Settings(KvStore(f))
        assertEquals(false, s.refresh60)
        assertEquals(1.1f, s.size)
        assertEquals(20, s.predictMs)
        assertEquals(1f, assertNotNull(s.axes).dot(Quat().setAxisAngle(1f, 0f, 0f, 1.5707964f)), 1e-5f)
        assertEquals(Presets.layout(Presets.DESK), s.lastLayout)
        assertEquals(Presets.CINEMA, s.startPreset)
        assertEquals(47900, s.port)
    }

    @Test
    fun valuesStayInRange() {
        val s = Settings(KvStore(file()))
        s.size = 3f
        assertEquals(Settings.MAX_SIZE, s.size)
        s.predictMs = -5
        assertEquals(0, s.predictMs)
        s.port = 80
        assertEquals(Settings.DEFAULT_PORT, s.port)
        s.startPreset = "nonsense"
        assertEquals(Presets.LAST, s.startPreset)
    }

    @Test
    fun unknownKeysAreKept() {
        val f = file()
        f.writeText("fromTheFuture=yes\n")
        Settings(KvStore(f)).size = 0.9f
        assertTrue(f.readText().contains("fromTheFuture=yes"))
    }

    @Test
    fun brokenAxesReadAsNone() {
        val f = file()
        f.writeText("axes=1,2,x,4\n")
        assertNull(Settings(KvStore(f)).axes)
    }
}
