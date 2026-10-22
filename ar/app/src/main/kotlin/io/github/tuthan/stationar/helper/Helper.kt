package io.github.tuthan.stationar.helper

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.adb.AdbClient
import io.github.tuthan.stationar.core.helper.Command
import io.github.tuthan.stationar.core.helper.Frames
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of the shell helper ([Main]): starts it over the app's own ADB (`app_process` with this APK as the
 * class path), connects to its abstract socket, and asks it things one at a time. [stop] closes the socket and the ADB
 * stream, which ends the helper. Worker threads only: calls block.
 */
class Helper(private val context: Context) {
    private var adb: AdbClient? = null
    @Volatile private var socket: LocalSocket? = null
    private var input: DataInputStream? = null
    private var out: DataOutputStream? = null

    /** Started and not lost. Any thread, without waiting for a call in progress. */
    val running: Boolean get() = socket != null

    /** Starts the helper (once). `approval`: the Station shows "Allow USB debugging?" for the app's key. */
    @Synchronized
    @Throws(IOException::class)
    fun start(approval: AdbClient.Approval?) {
        if (socket != null) return
        val name = "stationar-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val apk = context.applicationInfo.sourceDir
        val a = AdbClient.connect(context, 60_000, approval)
        a.readForever()
        val ready = CountDownLatch(1)
        // nohup: when the app dies, its ADB connection goes too, and the helper must outlive that to close the tasks it
        // put on AR's displays; it exits on its own once the app's socket closes.
        val stream = a.stream("CLASSPATH=$apk exec nohup app_process /system/bin ${Main::class.java.name} $name ${Process.myUid()}")
        Thread({
            val line = StringBuilder()
            try {
                while (true) {
                    val b = stream.read() ?: break
                    for (ch in String(b, Charsets.UTF_8)) {
                        if (ch != '\n') {
                            line.append(ch)
                            continue
                        }
                        if (line.toString() == "READY") ready.countDown() else AppLog.i("helper: $line")
                        line.setLength(0)
                    }
                }
            } catch (e: IOException) {
                // The connection closed (stop, or the app is going).
            }
            AppLog.i("helper: ended")
            ready.countDown()
        }, "stationar-helper-out").start()
        if (!ready.await(10, TimeUnit.SECONDS)) {
            a.close()
            throw IOException("the helper didn't start")
        }
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            a.close()
            throw IOException("couldn't reach the helper: ${e.message}")
        }
        adb = a
        socket = s
        input = DataInputStream(s.inputStream)
        out = DataOutputStream(s.outputStream)
        AppLog.i("helper: started")
    }

    /** Asks the helper; the payload after `OK` (maybe empty), or null on `ERR` or a broken connection (logged). */
    @Synchronized
    fun call(cmd: Command): String? {
        val o = out ?: return null
        val i = input ?: return null
        return try {
            Frames.write(o, cmd.encode())
            val reply = Frames.read(i)
            when {
                reply == "OK" -> ""
                reply.startsWith("OK ") -> reply.substring(3)
                else -> {
                    AppLog.w("helper: ${cmd.encode()}: $reply")
                    null
                }
            }
        } catch (e: IOException) {
            AppLog.w("helper: lost (${e.message})")
            stop()
            null
        }
    }

    /** Ends the helper: seeing its socket close, it closes the tasks it put on AR's displays, and exits. */
    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
        out = null
        adb?.close()
        adb = null
    }
}
