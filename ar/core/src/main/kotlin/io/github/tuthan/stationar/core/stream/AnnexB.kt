package io.github.tuthan.stationar.core.stream

/**
 * Cuts an H.265 or H.264 Annex-B byte stream into access units, at its access-unit delimiters (NAL type 35 in HEVC,
 * 9 in AVC): the sender inserts one before every picture. Bytes arrive in any pieces; start codes split across reads
 * are found all the same. Each access unit goes to [sink] as (buffer, length), the buffer valid only during the call.
 *
 * One buffer, grown to the largest access unit seen and then reused: nothing is allocated per unit.
 */
class AnnexB(private val codec: Codec, private val maxUnit: Int = 8 shl 20, private val sink: Sink) {
    enum class Codec { HEVC, AVC }

    fun interface Sink {
        fun onAccessUnit(data: ByteArray, length: Int)
    }

    private var buf = ByteArray(256 * 1024)
    private var len = 0
    /** Where scanning resumes: bytes before it hold no delimiter not yet acted on. */
    private var scan = 0
    /** Units dropped because they outgrew [maxUnit] (a stream without delimiters, or garbage). */
    var dropped = 0
        private set

    fun feed(data: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        ensure(len + n)
        System.arraycopy(data, off, buf, len, n)
        len += n
        cut()
    }

    /** Hands over what's buffered as a last access unit (the stream ended). */
    fun flush() {
        if (len > 0) sink.onAccessUnit(buf, len)
        len = 0
        scan = 0
    }

    private fun cut() {
        var i = maxOf(scan, 0)
        // A delimiter is 00 00 01 <header>; the header byte must have arrived to tell its type.
        while (i + 3 < len) {
            if (buf[i] == ZERO && buf[i + 1] == ZERO && buf[i + 2] == ONE) {
                val header = buf[i + 3].toInt() and 0xff
                if (isDelimiter(header)) {
                    // The unit starts at this start code, or at the zero before it (a four-byte start code).
                    val start = if (i > 0 && buf[i - 1] == ZERO) i - 1 else i
                    if (start > 0) {
                        sink.onAccessUnit(buf, start)
                        System.arraycopy(buf, start, buf, 0, len - start)
                        len -= start
                        i -= start
                    }
                    i += 4
                    continue
                }
                i += 3
                continue
            }
            i++
        }
        scan = i
        if (len > maxUnit) {
            dropped++
            len = 0
            scan = 0
        }
    }

    private fun isDelimiter(header: Int): Boolean = when (codec) {
        Codec.HEVC -> (header shr 1) and 0x3f == 35
        Codec.AVC -> header and 0x1f == 9
    }

    private fun ensure(size: Int) {
        if (size <= buf.size) return
        var n = buf.size
        while (n < size) n *= 2
        buf = buf.copyOf(minOf(n, maxUnit + 64 * 1024).coerceAtLeast(size))
    }

    companion object {
        private const val ZERO: Byte = 0
        private const val ONE: Byte = 1

        /** The NAL unit types in an access unit, in order (for parameter sets and key frames). */
        fun nalTypes(codec: Codec, data: ByteArray, length: Int, out: IntArray): Int {
            var n = 0
            var i = 0
            while (i + 3 < length && n < out.size) {
                if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                    val h = data[i + 3].toInt() and 0xff
                    out[n++] = if (codec == Codec.HEVC) (h shr 1) and 0x3f else h and 0x1f
                    i += 4
                } else {
                    i++
                }
            }
            return n
        }

        /** True when the unit holds the parameter sets a decoder needs first (VPS/SPS/PPS, or SPS/PPS). */
        fun hasParameterSets(codec: Codec, types: IntArray, n: Int): Boolean {
            for (k in 0 until n) {
                val t = types[k]
                if (codec == Codec.HEVC && t == 33) return true   // SPS
                if (codec == Codec.AVC && t == 7) return true
            }
            return false
        }
    }
}
