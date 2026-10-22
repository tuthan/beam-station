package io.github.tuthan.stationar.core.stream

import io.github.tuthan.stationar.core.store.KvStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing a laptop, once: Station AR shows a 6-digit PIN in AR, you type it on the laptop, and the laptop gets a
 * random 32-byte token for every later connection. Three wrong PINs lock pairing for a minute. The Station keeps only
 * each token's SHA-256 (so its settings file alone can't connect), under the laptop's name, in [store].
 *
 * Thread-safe.
 */
class Pairing(
    private val store: KvStore,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Result {
        data class Paired(val token: String) : Result()
        object Wrong : Result()
        data class Locked(val seconds: Int) : Result()
    }

    private var pin: String? = null
    private var pinFor: String? = null
    private var failures = 0
    private var lockedUntil = 0L

    /** Starts pairing `name`: the PIN to show, or null while locked (see [lockedSeconds]). */
    @Synchronized
    fun start(name: String): String? {
        if (lockedSeconds() > 0) return null
        val p = String.format("%06d", random.nextInt(1_000_000))
        pin = p
        pinFor = name
        return p
    }

    /** Seconds left of a lockout, or 0. */
    @Synchronized
    fun lockedSeconds(): Int {
        val left = lockedUntil - clock()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    /** The laptop's answer to the PIN shown for `name`. */
    @Synchronized
    fun answer(name: String, typed: String): Result {
        val left = lockedSeconds()
        if (left > 0) return Result.Locked(left)
        val expected = pin
        if (expected == null || pinFor != name || !MessageDigest.isEqual(expected.toByteArray(), typed.toByteArray())) {
            failures++
            pin = null
            if (failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = clock() + LOCK_MS
                return Result.Locked(lockedSeconds())
            }
            return Result.Wrong
        }
        pin = null
        failures = 0
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = hex(bytes)
        store.put(KEY_PREFIX + safeKey(name), hex(sha256(token)))
        return Result.Paired(token)
    }

    /** Pairing that was started and not answered (the connection dropped): the PIN stops working. */
    @Synchronized
    fun cancel() {
        pin = null
        pinFor = null
    }

    /** The paired laptop's name for this token, or null when it isn't one of ours. */
    @Synchronized
    fun laptopFor(token: String): String? {
        val hash = hex(sha256(token)).toByteArray()
        var found: String? = null
        for (k in store.keys()) {
            if (!k.startsWith(KEY_PREFIX)) continue
            // Constant time per entry, and every entry checked.
            if (MessageDigest.isEqual(store.get(k)?.toByteArray() ?: continue, hash)) found = k.removePrefix(KEY_PREFIX)
        }
        return found
    }

    /** The paired laptops' names. */
    @Synchronized
    fun laptops(): List<String> = store.keys().filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }

    /** Forgets a laptop: its token stops working, and its next connection must pair again. */
    @Synchronized
    fun forget(name: String) {
        store.remove(KEY_PREFIX + safeKey(name))
    }

    companion object {
        const val MAX_FAILURES = 3
        const val LOCK_MS = 60_000L
        private const val KEY_PREFIX = "laptop."

        /** A laptop name as a store key: what the store's keys allow, the rest as '_'. */
        fun safeKey(name: String) =
            name.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_' }.joinToString("").take(56)

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))

        fun hex(b: ByteArray): String = buildString { for (x in b) append(String.format("%02x", x.toInt() and 0xff)) }
    }
}
