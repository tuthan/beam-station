package io.github.tuthan.stationar.core.stream

import io.github.tuthan.stationar.core.store.KvStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private fun tempFile(): File = Files.createTempDirectory("pair").toFile().resolve("laptops.txt")
    private var now = 1_000_000L
    private fun pairing(f: File = tempFile()) = Pairing(KvStore(f), clock = { now })

    @Test
    fun theRightPinGivesATokenThatWorksLater() {
        val f = tempFile()
        val p = pairing(f)
        val pin = p.start("x1")!!
        assertTrue(Regex("[0-9]{6}").matches(pin))
        val r = p.answer("x1", pin)
        assertIs<Pairing.Result.Paired>(r)
        assertTrue(Regex("[0-9a-f]{64}").matches(r.token))
        // A new Pairing over the same file (Station AR restarted) still knows it.
        assertEquals("x1", pairing(f).laptopFor(r.token))
        assertNull(pairing(f).laptopFor("00".repeat(32)))
        // Only the hash is stored.
        assertFalse(f.readText().contains(r.token))
        assertEquals(listOf("x1"), pairing(f).laptops())
    }

    @Test
    fun aPinWorksOnceAndOnlyForItsLaptop() {
        val p = pairing()
        val pin = p.start("x1")!!
        assertEquals(Pairing.Result.Wrong, p.answer("other", pin))
        assertEquals(Pairing.Result.Wrong, p.answer("x1", pin))   // spent by the wrong try
        val pin2 = p.start("x1")!!
        assertIs<Pairing.Result.Paired>(p.answer("x1", pin2))
        assertEquals(Pairing.Result.Wrong, p.answer("x1", pin2))
    }

    @Test
    fun threeWrongPinsLockForAMinute() {
        val p = pairing()
        p.start("x1")
        assertEquals(Pairing.Result.Wrong, p.answer("x1", "000000x"))
        p.start("x1")
        assertEquals(Pairing.Result.Wrong, p.answer("x1", "zzzzzz"))
        p.start("x1")
        assertEquals(Pairing.Result.Locked(60), p.answer("x1", "zzzzzz"))
        assertNull(p.start("x1"))
        now += 30_000
        assertEquals(30, p.lockedSeconds())
        assertEquals(Pairing.Result.Locked(30), p.answer("x1", "123456"))
        now += 30_001
        assertEquals(0, p.lockedSeconds())
        val pin = assertNotNull(p.start("x1"))
        assertIs<Pairing.Result.Paired>(p.answer("x1", pin))
    }

    @Test
    fun pairingAgainReplacesTheOldToken() {
        val p = pairing()
        val first = (p.answer("x1", p.start("x1")!!) as Pairing.Result.Paired).token
        val second = (p.answer("x1", p.start("x1")!!) as Pairing.Result.Paired).token
        assertNull(p.laptopFor(first))
        assertEquals("x1", p.laptopFor(second))
        p.forget("x1")
        assertNull(p.laptopFor(second))
        assertTrue(p.laptops().isEmpty())
    }

    @Test
    fun cancelledPairingTakesNoPin() {
        val p = pairing()
        val pin = p.start("x1")!!
        p.cancel()
        assertEquals(Pairing.Result.Wrong, p.answer("x1", pin))
    }

    @Test
    fun namesBecomeSafeKeys() {
        assertEquals("hung_s_laptop", Pairing.safeKey("hung's laptop"))
        assertEquals("caf_", Pairing.safeKey("café"))
        assertTrue(KvStore.isKey("laptop." + Pairing.safeKey("x".repeat(80))))
    }
}
