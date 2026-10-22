package io.github.tuthan.beamstation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Picture settings for XGIMI projectors, whose own are Chinese only: picture mode, HDR and motion smoothing, through
 * XGIMI's picture service (IGimiVideo, exported, with no permission), which hands them to the projector's platform as
 * XGIMI's Settings does. The service can't list a model's picture modes (XGIMI's Settings keeps a list per model), so
 * this is only on the models listed here: the Play 6. Game mode isn't here: its on/off is kept per HDMI input by XGIMI's
 * Settings, and IGimiVideo's game call only picks its latency. Its calls block: never on the main thread.
 */
internal object Picture {
    private val SERVICE = ComponentName("com.xgimi.xgimiservice", "com.xgimi.xgimiservice.services.XgimiServices")
    private const val XGIMI = "com.xgimi.aidl.IXgimiService"
    private const val VIDEO = "com.xgimi.aidl.IGimiVideo"
    /** IXgimiService's getGimiVideo, then IGimiVideo's calls, numbered as XGIMI's own client numbers them. */
    private const val GET_VIDEO = 8
    private const val GET_MODE = 1
    private const val SET_MODE = 2
    private const val SET_MOTION = 17
    private const val GET_MOTION = 18
    private const val SET_HDR = 34
    private const val GET_HDR = 35

    /** The Play 6's picture modes in its menu's order: AI, Movie, Sport, TV, Custom, Office, Performance. */
    @JvmField val MODES = intArrayOf(10, 1, 9, 7, 3, 25, 5)
    /** Motion smoothing: off, low, medium, high; XGIMI's menu has it in Movie, TV and Custom only. */
    @JvmField val MOTION = intArrayOf(0, 1, 2, 3)
    private val MOTION_MODES = intArrayOf(1, 7, 3)

    class State {
        @JvmField var mode = 0
        @JvmField var motion = 0
        @JvmField var hdr = false

        fun hasMotion() = mode in MOTION_MODES
    }

    @JvmStatic
    fun available() = Xgimi.PLAY_6

    @JvmStatic
    fun indexOf(values: IntArray, v: Int) = values.indexOf(v)

    /** The settings now, or null when the service can't be reached. */
    @JvmStatic
    fun read(c: Context): State? = withVideo(c, ::readAll)

    private fun readAll(video: IBinder): State {
        val s = State()
        s.mode = getInt(video, GET_MODE)
        s.hdr = getInt(video, GET_HDR) != 0
        s.motion = getInt(video, GET_MOTION)
        return s
    }

    @JvmStatic
    fun setMode(c: Context, mode: Int) = set(c, SET_MODE, mode)

    @JvmStatic
    fun setHdr(c: Context, on: Boolean) = set(c, SET_HDR, if (on) 1 else 0)   // a boolean goes in a Parcel as an int

    @JvmStatic
    fun setMotion(c: Context, level: Int) = set(c, SET_MOTION, level)

    /** Changes one setting, then reads them all back (a picture mode brings its own HDR and motion). */
    private fun set(c: Context, code: Int, value: Int): State? = withVideo(c) { video ->
        call(video, VIDEO, code, { it.writeInt(value) }) {}
        AppLog.w("picture: call $code = $value")
        SystemClock.sleep(300)   // the platform applies some of them on its own thread
        readAll(video)
    }

    private fun getInt(b: IBinder, code: Int) = call(b, VIDEO, code, {}) { it.readInt() }

    /** One call on a binder: the interface token and `args` in, `reply` reads what comes back. */
    private fun <R> call(b: IBinder, token: String, code: Int, args: (Parcel) -> Unit, reply: (Parcel) -> R): R {
        val input = Parcel.obtain()
        val out = Parcel.obtain()
        try {
            input.writeInterfaceToken(token)
            args(input)
            b.transact(code, input, out, 0)
            out.readException()
            return reply(out)
        } finally {
            input.recycle()
            out.recycle()
        }
    }

    /** Binds XGIMI's service, runs `run` on its picture interface, unbinds. Null when any of it fails. */
    private fun <T> withVideo(c: Context, run: (IBinder) -> T): T? {
        val app = c.applicationContext
        val ready = CountDownLatch(1)
        var service: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            if (!app.bindService(Intent(SERVICE.className).setComponent(SERVICE), conn, Context.BIND_AUTO_CREATE)) {
                AppLog.w("picture: XGIMI's service isn't there")
                return null
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                AppLog.w("picture: XGIMI's service didn't answer")
                return null
            }
            val video: IBinder? = call(service!!, XGIMI, GET_VIDEO, {}) { it.readStrongBinder() }
            return video?.let(run)
        } catch (e: Exception) {   // RemoteException, RuntimeException or InterruptedException
            AppLog.w("picture: $e")
            return null
        } finally {
            try {
                app.unbindService(conn)
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }
}
