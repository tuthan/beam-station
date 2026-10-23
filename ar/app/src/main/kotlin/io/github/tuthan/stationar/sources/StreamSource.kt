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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A laptop monitor: the laptop's HEVC (or AVC) stream, decoded by the Station's hardware decoder straight into this
 * screen's SurfaceTexture. The source outlives connections: a laptop that reconnects carries on in the same screen,
 * and the last picture stays up meanwhile; a stream gone for [ENDED_MS] shows the card, saying so.
 *
 * [play] runs on the connection's thread: it reads, cuts access units ([AnnexB]) and queues them; a second thread
 * hands decoded frames to the Surface as soon as they're out.
 */
class StreamSource(name: String) : SurfaceSource(name, 1920, 1080) {
    private val lock = Object()
    private var current: StreamServer.Connection? = null
    private var playing: Thread? = null
    /** The Surface the decoder draws into while a stream plays. */
    @Volatile private var decoding: Surface? = null
    /** The connection once it was answered OK: lines to the laptop ([tell]) only go after that answer. */
    @Volatile private var playingOn: StreamServer.Connection? = null

    /** Access units dropped (waiting for a key frame, or too big), since the last log line. */
    @Volatile private var droppedUnits = 0

    /** The last stream's picture size, 0 before one: a screen takes its shape. */
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    override fun onSurface(surface: Surface) {
        synchronized(lock) {
            // A new Surface after the GL context was lost: the decoder's is gone, so the stream ends and the laptop,
            // reconnecting, starts again into this one.
            val old = decoding
            if (old != null && old !== surface) current?.close()
            lock.notifyAll()
        }
    }

    override fun onSurfaceGone() {
        // The decoder draws into the Surface: end the stream first, and give the decoder a moment to let go (this is
        // the GL thread). A decoder still holding it after that only gets an error when the Surface goes, and ends.
        val t: Thread?
        synchronized(lock) {
            current?.close()
            t = playing
        }
        if (t != null && t !== Thread.currentThread()) t.join(GONE_WAIT_MS)
    }

    /** True while a laptop streams to it. */
    val connected: Boolean get() = synchronized(lock) { current != null }

    /** Ends the stream now (its laptop was forgotten); the screen keeps its last picture. */
    fun disconnect() {
        synchronized(lock) { current }?.close()
    }

    /** Sends the laptop a line on this stream's connection (not on the main thread); false when there's none or it failed. */
    fun tell(line: String): Boolean {
        val conn = playingOn ?: return false
        return try {
            conn.answer(line)
            true
        } catch (e: IOException) {
            AppLog.i("$name: couldn't tell the laptop $line: ${e.message}")
            false
        }
    }

    /** You closed this screen: the laptop is told ([Handshake.GONE]) and the stream ends. */
    fun gone() {
        tell(Handshake.GONE)
        disconnect()
    }

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
            if (previousThread?.isAlive == true) {
                // The old decoder hasn't let go: the Station's decoder might not take a second one. The laptop retries.
                AppLog.w("$name: the previous stream is still ending; refused for now")
                conn.answer("CLOSED")
                return
            }
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
            decoding = surface
            label = laptop
            width = hello.width
            height = hello.height
            drain = Thread({ drain(c) }, "stationar-drain").apply { start() }
            conn.answer("OK")
            playingOn = conn
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
                // A connection that replaced this one keeps its own state.
                if (playingOn === conn) playingOn = null
                if (current === conn) {
                    current = null
                    playing = null
                    decoding = null
                    // The laptop's own reconnects come within this: only a stream that stays gone shows the card.
                    later.schedule({ synchronized(lock) { if (current == null) note = ENDED_NOTE } }, ENDED_MS, TimeUnit.MILLISECONDS)
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
                    if (note != null) note = null
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
        private const val GONE_WAIT_MS = 500L

        /** How long a stream stays gone before its screen shows the card: past the laptop's reconnects (0.5-3 s). */
        private const val ENDED_MS = 4000L
        private const val ENDED_NOTE = "Not streaming: run station-ar-send on the laptop"

        /** Where a stream's end is looked at again. */
        private val later: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "stationar-stream-end").apply { isDaemon = true }
        }

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
