package io.github.tuthan.stationar.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** six.hevc and six.h264: six 128x72 frames from ffmpeg (libx265 / libx264, key frame every 3, AUDs inserted). */
class AnnexBTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/stream/$name")!!.readBytes()

    private fun split(codec: AnnexB.Codec, data: ByteArray, piece: Int): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val a = AnnexB(codec) { b, n -> units.add(b.copyOf(n)) }
        var i = 0
        while (i < data.size) {
            val n = minOf(piece, data.size - i)
            a.feed(data, i, n)
            i += n
        }
        a.flush()
        return units
    }

    private fun types(codec: AnnexB.Codec, unit: ByteArray): List<Int> {
        val out = IntArray(32)
        val n = AnnexB.nalTypes(codec, unit, unit.size, out)
        return out.take(n)
    }

    @Test
    fun hevcCutsAtEveryDelimiter() {
        val data = fixture("six.hevc")
        val units = split(AnnexB.Codec.HEVC, data, data.size)
        assertEquals(6, units.size)
        for (u in units) assertEquals(35, types(AnnexB.Codec.HEVC, u).first())
        // Nothing lost, nothing added.
        assertEquals(data.toList(), units.flatMap { it.toList() })
        assertEquals(listOf(35, 32, 33, 34, 39, 20), types(AnnexB.Codec.HEVC, units[0]))
    }

    @Test
    fun avcCutsAtEveryDelimiter() {
        val data = fixture("six.h264")
        val units = split(AnnexB.Codec.AVC, data, data.size)
        assertEquals(6, units.size)
        for (u in units) assertEquals(9, types(AnnexB.Codec.AVC, u).first())
        assertEquals(data.toList(), units.flatMap { it.toList() })
    }

    @Test
    fun anyPieceSizeGivesTheSameUnits() {
        for ((codec, name) in listOf(AnnexB.Codec.HEVC to "six.hevc", AnnexB.Codec.AVC to "six.h264")) {
            val data = fixture(name)
            val whole = split(codec, data, data.size).map { it.toList() }
            for (piece in listOf(1, 2, 3, 4, 5, 7, 64, 1000)) {
                assertEquals(whole, split(codec, data, piece).map { it.toList() }, "$name in pieces of $piece")
            }
        }
    }

    @Test
    fun parameterSetsMarkTheKeyFrames() {
        val units = split(AnnexB.Codec.HEVC, fixture("six.hevc"), 4096)
        val out = IntArray(32)
        val withSets = units.indices.filter {
            AnnexB.hasParameterSets(AnnexB.Codec.HEVC, out, AnnexB.nalTypes(AnnexB.Codec.HEVC, units[it], units[it].size, out))
        }
        // libx265 uses B-frames, so in decode order the second key frame (a CRA) comes second.
        assertEquals(listOf(0, 1), withSets)
        val avc = split(AnnexB.Codec.AVC, fixture("six.h264"), 4096)
        val avcSets = avc.indices.filter {
            AnnexB.hasParameterSets(AnnexB.Codec.AVC, out, AnnexB.nalTypes(AnnexB.Codec.AVC, avc[it], avc[it].size, out))
        }
        assertEquals(listOf(0, 3), avcSets)
    }

    @Test
    fun threeAndFourByteStartCodes() {
        // AUD (HEVC header 0x46 0x01), a slice, AUD with a 3-byte start code, a slice.
        val s = byteArrayOf(0, 0, 0, 1, 0x46, 1, 0x50, 0, 0, 1, 0x02, 1, 7, 7, 0, 0, 1, 0x46, 1, 0x50, 0, 0, 1, 0x02, 1, 9)
        val units = split(AnnexB.Codec.HEVC, s, 1)
        assertEquals(2, units.size)
        assertEquals(14, units[0].size)
        assertEquals(listOf<Byte>(0, 0, 1, 0x46), units[1].take(4))
    }

    @Test
    fun aStreamWithoutDelimitersIsDroppedNotHoarded() {
        val units = ArrayList<Int>()
        val a = AnnexB(AnnexB.Codec.HEVC, maxUnit = 1000) { _, n -> units.add(n) }
        val junk = ByteArray(300) { 7 }
        repeat(10) { a.feed(junk, 0, junk.size) }
        assertTrue(a.dropped >= 2)
        assertTrue(units.isEmpty())
        // And it recovers when delimiters come.
        val good = fixture("six.hevc")
        a.feed(good, 0, good.size)
        a.flush()
        assertTrue(units.size >= 5)
    }
}
