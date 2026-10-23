package io.github.tuthan.stationar.core.stream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandshakeTest {
    private val token = "ab".repeat(32)

    @Test
    fun pairAndHelloRoundTrip() {
        assertEquals(Handshake.Request.Pair("hung-x1 laptop"), Handshake.parseRequest(Handshake.pair("hung-x1 laptop")))
        val hello = Handshake.parseRequest(Handshake.hello(token, "1", 1920, 1080, 60, "hevc"))
        assertEquals(Handshake.Request.Hello(token, "1", 1920, 1080, 60, AnnexB.Codec.HEVC), hello)
        assertEquals("laptop:1", (hello as Handshake.Request.Hello).source)
        val third = Handshake.parseRequest(Handshake.hello(token, "2", 1920, 1080, 60, "hevc")) as Handshake.Request.Hello
        assertEquals("laptop:2", third.source)
        val wide = Handshake.parseRequest(Handshake.hello(token, "wide", 3840, 1080, 60, "avc")) as Handshake.Request.Hello
        assertEquals("wide", wide.source)
        assertEquals(AnnexB.Codec.AVC, wide.codec)
        assertFalse(wide.toString().contains(token))
    }

    @Test
    fun otherVersionsAreRecognised() {
        assertEquals(Handshake.Request.OtherVersion("2"), Handshake.parseRequest("STATIONAR 2 HELLO whatever"))
        assertNull(Handshake.parseRequest("STATIONAR x2 HELLO"))
    }

    @Test
    fun junkIsRefused() {
        for (line in listOf(
            "", "GET / HTTP/1.1", "STATIONAR 1", "STATIONAR 1 PAIR ", "STATIONAR 1 PAIR " + "x".repeat(41),
            "STATIONAR 1 PAIR bad\u0001name", "STATIONAR 1 HELLO short 0 1920 1080 60 hevc",
            "STATIONAR 1 HELLO ${token.uppercase()} 0 1920 1080 60 hevc",
            "STATIONAR 1 HELLO $token 3 1920 1080 60 hevc",
            "STATIONAR 1 HELLO $token 0 1920 1080 60 vp9",
            "STATIONAR 1 HELLO $token 0 99999 1080 60 hevc",
            "STATIONAR 1 HELLO $token 0 1920 1080 0 hevc",
            "STATIONAR 1 HELLO $token 0 1920 1080 60 hevc extra",
            "STATIONAR 1 DANCE",
        )) assertNull(Handshake.parseRequest(line), line)
    }

    @Test
    fun pins() {
        assertEquals("042917", Handshake.parsePin("PIN 042917"))
        assertNull(Handshake.parsePin("PIN 42917"))
        assertNull(Handshake.parsePin("PIN 0429170"))
        assertNull(Handshake.parsePin("pin 042917"))
    }

    @Test
    fun linesAreBounded() {
        val out = ByteArrayOutputStream()
        Handshake.writeLine(out, "OK")
        Handshake.writeLine(out, "TOKEN $token")
        val input = ByteArrayInputStream((out.toString("US-ASCII") + "CRLF\r\n").toByteArray())
        assertEquals("OK", Handshake.readLine(input))
        assertEquals("TOKEN $token", Handshake.readLine(input))
        assertEquals("CRLF", Handshake.readLine(input))
        assertFailsWith<IOException> { Handshake.readLine(input) }
        assertFailsWith<IOException> { Handshake.readLine(ByteArrayInputStream(ByteArray(300) { 'a'.code.toByte() })) }
        assertFailsWith<IOException> { Handshake.readLine(ByteArrayInputStream(byteArrayOf(0x41, 0, 0x0a))) }
        assertFailsWith<IllegalArgumentException> { Handshake.writeLine(out, "two\nlines") }
        assertTrue(Handshake.MAX_LINE >= Handshake.hello(token, "wide", 4096, 2160, 120, "hevc").length)
    }
}
