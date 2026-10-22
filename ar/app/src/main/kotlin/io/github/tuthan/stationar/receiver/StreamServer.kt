package io.github.tuthan.stationar.receiver

import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.core.stream.Handshake
import io.github.tuthan.stationar.core.stream.Pairing
import java.io.BufferedInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Station AR's receiver for laptop monitors (docs/protocol.md): TLS 1.3 on [port], open only while AR is. A laptop
 * pairs once with the PIN [Listener.onPairing] shows, then streams one screen per connection. A new connection for a
 * screen replaces the one before it (a laptop that reconnected after the network dropped).
 */
class StreamServer(private val port: Int, private val pairing: Pairing, private val listener: Listener) {
    interface Listener {
        /** A laptop asks to pair: show `pin` (null: pairing ended), on a connection thread. */
        fun onPairing(name: String, pin: String?)

        /**
         * A paired laptop's stream for a screen, on its connection thread: answers OK and plays it until the stream
         * ends, or answers why not (`BUSY`, `NO_DECODER`, `CLOSED`) and returns.
         */
        fun onStream(hello: Handshake.Request.Hello, laptop: String, connection: Connection)
    }

    /** One laptop connection, as the code that plays its stream sees it. */
    class Connection(private val socket: SSLSocket, val input: BufferedInputStream) {
        @Throws(IOException::class)
        fun answer(line: String) = Handshake.writeLine(socket.outputStream, line)

        /** Reads give up after `ms` without a byte. */
        fun timeout(ms: Int) {
            socket.soTimeout = ms
        }

        fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    @Volatile private var server: SSLServerSocket? = null
    private var thread: Thread? = null
    private val pairingBusy = AtomicBoolean(false)
    private val open = HashSet<SSLSocket>()

    /** Opens the port and starts accepting (throws if the port is taken). */
    fun start() {
        val s = Tls.serverSocket(port)
        server = s
        thread = Thread({ acceptLoop(s) }, "stationar-accept").apply { start() }
        AppLog.i("receiver: listening on $port (TLS 1.3)")
    }

    /** Closes the port and every connection. */
    fun stop() {
        val s = server ?: return
        server = null
        try {
            s.close()
        } catch (_: IOException) {
        }
        synchronized(open) {
            for (c in open) try {
                c.close()
            } catch (_: IOException) {
            }
            open.clear()
        }
        thread?.join(1000)
        thread = null
        AppLog.i("receiver: closed")
    }

    private fun acceptLoop(s: SSLServerSocket) {
        while (server === s) {
            val c = try {
                s.accept() as SSLSocket
            } catch (e: IOException) {
                if (server === s) AppLog.w("receiver: accept failed: $e")
                break
            }
            synchronized(open) { open.add(c) }
            Thread({ serve(c) }, "stationar-conn").start()
        }
    }

    private fun serve(c: SSLSocket) {
        val peer = c.inetAddress?.hostAddress ?: "?"
        try {
            c.soTimeout = HANDSHAKE_TIMEOUT_MS
            c.tcpNoDelay = true
            c.startHandshake()
            val input = BufferedInputStream(c.inputStream, 64 * 1024)
            val conn = Connection(c, input)
            when (val req = Handshake.parseRequest(Handshake.readLine(input))) {
                is Handshake.Request.Pair -> pair(req.name, conn)
                is Handshake.Request.Hello -> hello(req, peer, conn)
                is Handshake.Request.OtherVersion -> conn.answer("VERSION ${Handshake.VERSION}")
                null -> AppLog.w("receiver: $peer sent something else; closed")
            }
        } catch (e: SocketTimeoutException) {
            AppLog.i("receiver: $peer timed out")
        } catch (e: IOException) {
            AppLog.i("receiver: $peer: ${e.message}")
        } catch (e: RuntimeException) {
            AppLog.w("receiver: $peer: $e")
        } finally {
            try {
                c.close()
            } catch (_: IOException) {
            }
            synchronized(open) { open.remove(c) }
        }
    }

    private fun pair(name: String, conn: Connection) {
        if (!pairingBusy.compareAndSet(false, true)) {
            conn.answer("BUSY")
            return
        }
        try {
            val pin = pairing.start(name)
            if (pin == null) {
                conn.answer("LOCKED ${pairing.lockedSeconds()}")
                return
            }
            AppLog.i("receiver: pairing \"$name\"")
            listener.onPairing(name, pin)
            conn.answer("PIN")
            // Time to read the PIN in the glasses and type it.
            conn.timeout(PIN_TIMEOUT_MS)
            val typed = try {
                Handshake.parsePin(Handshake.readLine(conn.input))
            } catch (e: IOException) {
                pairing.cancel()
                throw e
            }
            when (val r = pairing.answer(name, typed ?: "")) {
                is Pairing.Result.Paired -> {
                    conn.answer("TOKEN ${r.token}")
                    AppLog.i("receiver: paired \"$name\"")
                }
                Pairing.Result.Wrong -> conn.answer("DENIED")
                is Pairing.Result.Locked -> conn.answer("LOCKED ${r.seconds}")
            }
        } finally {
            listener.onPairing(name, null)
            pairingBusy.set(false)
        }
    }

    private fun hello(req: Handshake.Request.Hello, peer: String, conn: Connection) {
        val laptop = pairing.laptopFor(req.token)
        if (laptop == null) {
            AppLog.i("receiver: $peer: unknown token; denied")
            conn.answer("DENIED")
            return
        }
        AppLog.i("receiver: \"$laptop\" ($peer): $req")
        listener.onStream(req, laptop, conn)
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val PIN_TIMEOUT_MS = 120_000
        /** No bytes for this long ends a stream (the sender keeps a constant frame rate). */
        const val STREAM_TIMEOUT_MS = 5_000
    }
}
