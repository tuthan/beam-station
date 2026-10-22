package io.github.tuthan.stationar.core.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The lines a laptop and Station AR exchange inside TLS before any video (docs/protocol.md). ASCII, one per line,
 * at most [MAX_LINE] bytes; anything else closes the connection.
 *
 *   laptop                                           Station
 *   STATIONAR 1 PAIR <name>                     ->
 *                                               <-   PIN                (the PIN is shown in AR)
 *   PIN <6 digits>                              ->
 *                                               <-   TOKEN <64 hex> | DENIED | LOCKED <seconds>
 *
 *   STATIONAR 1 HELLO <token> <screen> <w> <h> <fps> <codec>  ->
 *                                               <-   OK | DENIED | BUSY | NO_DECODER | CLOSED | VERSION 1
 *   then the Annex-B video, laptop to Station, until either side closes.
 */
object Handshake {
    const val VERSION = 1
    const val MAX_LINE = 256

    sealed class Request {
        data class Pair(val name: String) : Request()

        data class Hello(
            val token: String,
            val screen: String,
            val width: Int,
            val height: Int,
            val fps: Int,
            val codec: AnnexB.Codec,
        ) : Request() {
            /** The scene source this stream feeds: `laptop:<n>`, or `wide` for both halves of a wide stream. */
            val source: String get() = if (screen == "wide") "wide" else "laptop:$screen"

            override fun toString() = "HELLO screen $screen ${width}x$height@$fps $codec"   // never the token
        }

        /** A version this Station doesn't speak: answer VERSION and close. */
        data class OtherVersion(val version: String) : Request()
    }

    private val NAME = Regex("[A-Za-z0-9 ._-]{1,40}")
    private val TOKEN = Regex("[0-9a-f]{64}")
    private val PIN = Regex("[0-9]{6}")
    private val SCREEN = Regex("[01]|wide")

    /** The first line, parsed; null when it isn't one of ours (close the connection). */
    fun parseRequest(line: String): Request? {
        val p = line.split(' ')
        if (p.size < 3 || p[0] != "STATIONAR") return null
        if (p[1] != VERSION.toString()) return if (p[1].all { it.isDigit() } && p[1].length <= 3) Request.OtherVersion(p[1]) else null
        return when (p[2]) {
            "PAIR" -> {
                val name = line.substringAfter("PAIR ", "")
                if (NAME.matches(name)) Request.Pair(name) else null
            }
            "HELLO" -> {
                if (p.size != 9 || !TOKEN.matches(p[3]) || !SCREEN.matches(p[4])) return null
                val w = p[5].toIntOrNull() ?: return null
                val h = p[6].toIntOrNull() ?: return null
                val fps = p[7].toIntOrNull() ?: return null
                val codec = when (p[8]) {
                    "hevc" -> AnnexB.Codec.HEVC
                    "avc" -> AnnexB.Codec.AVC
                    else -> return null
                }
                if (w !in 64..4096 || h !in 64..2160 || fps !in 1..120) return null
                Request.Hello(p[3], p[4], w, h, fps, codec)
            }
            else -> null
        }
    }

    /** The laptop's PIN line; null when it isn't one. */
    fun parsePin(line: String): String? {
        if (!line.startsWith("PIN ")) return null
        val pin = line.substring(4)
        return if (PIN.matches(pin)) pin else null
    }

    fun pair(name: String) = "STATIONAR $VERSION PAIR $name"

    fun hello(token: String, screen: String, w: Int, h: Int, fps: Int, codec: String) =
        "STATIONAR $VERSION HELLO $token $screen $w $h $fps $codec"

    /**
     * Reads one line (up to '\n', which isn't returned; a '\r' before it is dropped). Throws when the line is longer
     * than [MAX_LINE], isn't printable ASCII, or the stream ends first.
     */
    @Throws(IOException::class)
    fun readLine(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("closed before the line ended")
            if (c == '\n'.code) break
            if (b.length >= MAX_LINE) throw IOException("line too long")
            if (c == '\r'.code) continue
            if (c < 0x20 || c > 0x7e) throw IOException("not a text line")
            b.append(c.toChar())
        }
        return b.toString()
    }

    @Throws(IOException::class)
    fun writeLine(out: OutputStream, line: String) {
        require(line.length <= MAX_LINE && line.all { it.code in 0x20..0x7e }) { "bad line" }
        out.write((line + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
