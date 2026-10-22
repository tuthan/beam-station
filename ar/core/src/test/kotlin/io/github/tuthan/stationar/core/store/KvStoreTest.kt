package io.github.tuthan.stationar.core.store

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KvStoreTest {
    private fun tempFile(): File = Files.createTempDirectory("kv").toFile().resolve("store.txt")

    @Test
    fun roundTrip() {
        val f = tempFile()
        KvStore(f).apply {
            put("size", "1.2")
            put("name", "Hung's laptop")
            put("odd", "a=b\nline two \\ end")
        }
        val back = KvStore(f)
        assertEquals("1.2", back.get("size"))
        assertEquals(1.2f, back.getFloat("size", 0f))
        assertEquals("Hung's laptop", back.get("name"))
        assertEquals("a=b\nline two \\ end", back.get("odd"))
        assertEquals(listOf("size", "name", "odd"), back.keys())
    }

    @Test
    fun unknownKeysAreKept() {
        val f = tempFile()
        f.writeText("future.setting=42\nmode=1\n")
        val s = KvStore(f)
        s.put("mode", "0")
        assertEquals("future.setting=42\nmode=0\n", f.readText())
    }

    @Test
    fun brokenLinesAreSkipped() {
        val f = tempFile()
        f.writeText("no equals sign\n=empty key\nbad key!=1\ngood=2\n")
        val s = KvStore(f)
        assertEquals(listOf("good"), s.keys())
    }

    @Test
    fun defaults() {
        val s = KvStore(tempFile())
        assertEquals(7, s.getInt("missing", 7))
        assertTrue(s.getBoolean("missing", true))
        s.put("flag", "false")
        assertFalse(s.getBoolean("flag", true))
        s.put("n", "x")
        assertEquals(3, s.getInt("n", 3))
    }

    @Test
    fun removeAndDelete() {
        val f = tempFile()
        val s = KvStore(f)
        s.put("a", "1")
        s.remove("a")
        assertNull(KvStore(f).get("a"))
        s.put("b", "2")
        assertTrue(s.exists())
        s.delete()
        assertFalse(f.exists())
        assertNull(s.get("b"))
    }

    @Test
    fun noTempFileIsLeft() {
        val f = tempFile()
        KvStore(f).put("a", "1")
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun badKeysAreRefused() {
        assertFailsWith<IllegalArgumentException> { KvStore(tempFile()).put("has space", "1") }
    }
}
