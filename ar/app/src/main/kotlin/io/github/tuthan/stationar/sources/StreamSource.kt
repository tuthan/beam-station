package io.github.tuthan.stationar.sources

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.core.stream.AnnexB
import io.github.tuthan.stationar.core.stream.Handshake
import io.github.tuthan.stationar.receiver.StreamServer
import java.io.IOException

/**
 * A laptop monitor: the laptop's HEVC (or AVC) stream, decoded by the Station's hardware decoder straight into this
 * screen's SurfaceTexture. The source outlives connections: a laptop that reconnects carries on in the same screen,
 * and the last picture stays up meanwhile.
 *
 * [play] runs on the connection's thread: it reads, cuts access units ([AnnexB]) and queues them; a second thread
 * hands decoded frames to the Surface as soon as they're out.
 */
class StreamSource(name: String) : SurfaceSource(name, 1920, 1080) {
    private val lock = Object()
    private var current: StreamServer.Connection? = null
    private var playing: Thread? = null

    /** Access units dropped (waiting for a key frame, or too big), since the last log line. */
    @Volatile private var droppedUnits = 0

    override fun onSurface(surface: Surface) {
        synchronized(lock) { lock.notifyAll() }
    }

    override fun onSurfaceGone() {
        // The decoder draws into the Surface: end the stream first, and wait for the decoder to let go.
        val t: Thread?
        synchronized(lock) {
            current?.close()
            t = playing
        }
        if (t != null && t !== Thread.currentThread()) t.join(2000)
    }

    /** True while a laptop streams to it. */
    val connected: Boolean get() = synchronized(lock) { current != null }

    /**
     * Plays a stream until it ends (the laptop stopped, the network dropped, another connection replaced it, or the
     * screen went away). Answers OK first, or CLOSED when the screen has no Surface.
     */
    fun play(hello: Handshake.Request.Hello, laptop: String, conn: StreamServer.Connection) {
        val previous: StreamServer.Connection?
        val previousThread: Thread?
        synchronized(lock) {
            previous = current
            previousThread = playing
            current = conn
            playing = Thread.currentThread()
        }
        // A reconnect: the old connection ends and frees the decoder before this one makes its own.
        previous?.close()
        previousThread?.join(2000)
        var codec: MediaCodec? = null
        var drain: Thread? = null
        try {
            val surface = waitForSurface() ?: run {
                conn.answer("CLOSED")
                return
            }
            val decoderName = decoderFor(hello) ?: run {
                conn.answer("NO_DECODER")
                return
            }
            val c = MediaCodec.createByCodecName(decoderName)
            codec = c
            val f = format(hello).apply {
                // A key frame of a sharp desktop at 15-20 Mbps runs to a few hundred KB.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
                lowLatency(decoderName, this)
            }
            c.configure(f, surface, null, 0)
            c.start()
            label = laptop
            drain = Thread({ drain(c) }, "stationar-drain").apply { start() }
            conn.answer("OK")
            conn.timeout(StreamServer.STREAM_TIMEOUT_MS)
            AppLog.i("$name: playing \"$laptop\" with $decoderName")
            feed(c, hello.codec, conn)
        } catch (e: IOException) {
            AppLog.i("$name: stream ended: ${e.message}")
        } catch (e: RuntimeException) {
            // MediaCodec.CodecException, IllegalStateException: a stream the decoder can't take, or a Surface gone.
            AppLog.w("$name: decoder stopped", e)
        } finally {
            drain?.interrupt()
            try {
                codec?.stop()
            } catch (_: RuntimeException) {
            }
            drain?.join(500)
            codec?.release()
            conn.close()
            synchronized(lock) {
                if (current === conn) {
                    current = null
                    playing = null
                }
            }
        }
    }

    private fun waitForSurface(): Surface? {
        val until = System.currentTimeMillis() + 2000
        synchronized(lock) {
            while (true) {
                surface?.let { return it }
                val left = until - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
        }
    }

    private fun feed(c: MediaCodec, kind: AnnexB.Codec, conn: StreamServer.Connection) {
        val types = IntArray(32)
        var waitingForKey = true
        val splitter = AnnexB(kind) { data, length ->
            val n = AnnexB.nalTypes(kind, data, length, types)
            if (waitingForKey && !AnnexB.hasParameterSets(kind, types, n)) {
                droppedUnits++
                return@AnnexB
            }
            waitingForKey = false
            var index: Int
            do {
                index = c.dequeueInputBuffer(20_000)
                if (Thread.currentThread().isInterrupted) throw IOException("interrupted")
            } while (index < 0 && surface != null)
            if (index < 0) throw IOException("screen gone")
            val buf = c.getInputBuffer(index)!!
            if (length > buf.capacity()) {
                // Too big for the decoder's buffers: skip to the next key frame rather than show a broken one.
                c.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, 0)
                droppedUnits++
                waitingForKey = true
                return@AnnexB
            }
            buf.clear()
            buf.put(data, 0, length)
            c.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0)
        }
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = conn.input.read(buf)
            if (n < 0) throw IOException("the laptop closed the stream")
            splitter.feed(buf, 0, n)
        }
    }

    /** Renders each decoded frame at once, and logs every 10 s: frames, and arrival-to-decoded time. */
    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val lag = LongArray(1200)
        var frames = 0
        var since = System.nanoTime()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val index = c.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) {
                    val now = System.nanoTime()
                    c.releaseOutputBuffer(index, true)
                    if (frames < lag.size) lag[frames] = now / 1000 - info.presentationTimeUs
                    frames++
                    if (now - since >= 10_000_000_000L) {
                        val n = minOf(frames, lag.size)
                        java.util.Arrays.sort(lag, 0, n)
                        AppLog.i(String.format(java.util.Locale.US, "%s: %.1f fps decoded, arrival to decoded p50 %.1f p95 %.1f ms, %d units dropped",
                            name, frames * 1e9 / (now - since), lag[n / 2] / 1000.0, lag[n * 95 / 100] / 1000.0, droppedUnits))
                        frames = 0
                        droppedUnits = 0
                        since = now
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    AppLog.i("$name: decoding ${c.outputFormat}")
                }
            }
        } catch (_: IllegalStateException) {
            // The codec stopped.
        }
    }

    companion object {
        /** The decoder for this stream's format, or null when the Station has none (answer NO_DECODER). */
        fun decoderFor(hello: Handshake.Request.Hello): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format(hello))

        private fun format(hello: Handshake.Request.Hello): MediaFormat {
            val mime = if (hello.codec == AnnexB.Codec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            return MediaFormat.createVideoFormat(mime, hello.width, hello.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, hello.fps)
            }
        }

        private fun lowLatency(decoderName: String, f: MediaFormat) {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == decoderName } ?: return
            val caps = info.getCapabilitiesForType(f.getString(MediaFormat.KEY_MIME)!!)
            if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }
}
