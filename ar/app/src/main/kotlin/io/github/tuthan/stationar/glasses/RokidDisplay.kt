// Ported from Station Home's Rokid.kt (this repository), MIT, Copyright (c) 2026 Free My TV contributors.
package io.github.tuthan.stationar.glasses

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.adb.AdbClient
import java.io.IOException
import java.util.regex.Pattern

/**
 * The glasses' 2D/3D mode and refresh rate, through Rokid's `rokid_display` service (IRokidDisplayManager in
 * framework.jar; its setters check no permission). The service has no SELinux label of its own; the Station's SELinux
 * is permissive, so an app's lookup works there (logged as denied). Should a firmware update enforce it, the same
 * calls go through the box's own ADB (`service call`). Every call blocks: never on the main thread.
 */
class RokidDisplay(private val context: Context) {
    class State(@JvmField val mode: Int, @JvmField val fps: Int) {
        val is3d: Boolean get() = mode == MODE_3D
        override fun toString() = "mode $mode, $fps Hz"
    }

    private fun interface Calls {
        fun call(code: Int, arg: Int?): Int
    }

    /** The glasses' mode and refresh rate, or null when no glasses are plugged in or the service can't be reached. */
    @Synchronized
    fun read(): State? = run { calls -> if (calls.call(PLUGGED, null) == 0) null else State(calls.call(GET_MODE, null), calls.call(GET_FPS, null)) }

    /** Sets the mode (MODE_2D or MODE_3D) and waits for the glasses to come back with it; the state after, or null. */
    @Synchronized
    fun setMode(mode: Int): State? = set(SET_MODE, mode)

    /** Sets the refresh rate (60 or 120) and waits for the glasses to come back with it; the state after, or null. */
    @Synchronized
    fun setFps(fps: Int): State? = set(SET_FPS, fps)

    /**
     * A new mode or refresh rate retrains the HDMI link: the glasses read as unplugged for about two seconds, and what's
     * read meanwhile is stale, so it waits (up to 8 s) for them to come back with what was asked.
     */
    private fun set(code: Int, value: Int): State? = run { calls ->
        if (calls.call(PLUGGED, null) == 0) return@run null
        val ok = calls.call(code, value)
        AppLog.i("glasses: call $code = $value" + if (ok == 1) "" else " refused")
        val until = SystemClock.elapsedRealtime() + if (ok == 1) 8000 else 0
        do {
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < until && !settled(calls, code, value))
        State(calls.call(GET_MODE, null), calls.call(GET_FPS, null))
    }

    private fun settled(calls: Calls, code: Int, value: Int): Boolean {
        if (calls.call(PLUGGED, null) == 0) return false
        if (code == SET_FPS) return calls.call(GET_FPS, null) == value
        val mode = calls.call(GET_MODE, null)
        return if (value == MODE_3D) mode == MODE_3D else mode != MODE_3D
    }

    /** Runs `work` over the binder, or else over one ADB connection. Null when any of it fails. */
    private fun <T> run(work: (Calls) -> T): T? {
        val b = binder()
        try {
            if (b != null) return work(Calls { code, arg -> transact(b, code, arg) })
            AdbClient.connect(context, 10_000, null).use { adb ->
                return work(Calls { code, arg -> shell(adb, code, arg) })
            }
        } catch (e: Exception) {   // IOException or RuntimeException (nothing else is thrown here)
            AppLog.w("glasses: ${e.message}")
            return null
        }
    }

    private var binder: IBinder? = null
    private var binderTried = false

    /** Looked up once, and again after the service restarts (its old binder dies); without access it stays ADB. */
    private fun binder(): IBinder? {
        val known = binder
        if (binderTried && (known == null || known.isBinderAlive)) return known
        binderTried = true
        binder = try {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder?
        } catch (e: Exception) {   // ReflectiveOperationException or RuntimeException
            null
        }
        AppLog.i("glasses: rokid_display " + if (binder != null) "over the binder" else "over ADB (the app can't look it up)")
        return binder
    }

    private fun transact(b: IBinder, code: Int, arg: Int?): Int {
        val input = Parcel.obtain()
        val out = Parcel.obtain()
        try {
            input.writeInterfaceToken(DESCRIPTOR)
            if (arg != null) input.writeInt(arg)
            b.transact(code, input, out, 0)
            out.readException()
            return out.readInt()   // a boolean comes back as an int
        } catch (e: Exception) {   // RemoteException or RuntimeException
            throw IOException("rokid_display call $code: $e")
        } finally {
            input.recycle()
            out.recycle()
        }
    }

    private fun shell(adb: AdbClient, code: Int, arg: Int?): Int {
        val reply = adb.shell("service call $SERVICE $code" + if (arg == null) "" else " i32 $arg")
        val m = PARCEL.matcher(reply)
        if (!m.find() || m.group(1)!!.toLong(16) != 0L) throw IOException("rokid_display call $code: ${reply.trim()}")
        return m.group(2)!!.toLong(16).toInt()
    }

    companion object {
        private const val SERVICE = "rokid_display"
        private const val DESCRIPTOR = "com.rokid.display.IRokidDisplayManager"
        /** IRokidDisplayManager's transaction codes. */
        private const val SET_MODE = 4
        private const val GET_MODE = 6
        private const val PLUGGED = 7
        private const val GET_FPS = 14
        private const val SET_FPS = 15
        /** Display modes as getAirDisplayMode reports them; 3 is 2D at 120 Hz, which is what setting 120 Hz reads back as. */
        const val MODE_2D = 0
        const val MODE_3D = 1
        const val MODE_2D_120 = 3
        /** `service call`'s answer: the exception code (0 = none), then the int the call returned. */
        private val PARCEL = Pattern.compile("Result: Parcel\\(([0-9a-f]{8}) ([0-9a-f]{8})")
    }
}
