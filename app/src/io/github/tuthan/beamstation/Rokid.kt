package io.github.tuthan.beamstation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Rokid Station (the box for Rokid's AR glasses): the glasses' 2D/3D, refresh rate, picture scene and speaker mode,
 * through Rokid's `rokid_display` service (IRokidDisplayManager in framework.jar; its setters check no permission).
 * The service has no SELinux label of its own, and Android's policy doesn't let apps look up unlabelled services; the
 * Station's SELinux is permissive, so the lookup works there (logged as denied). Should a firmware update enforce it,
 * the same calls go through the TV's own ADB (`service call`), as the shell's are allowed. The
 * calls run one at a time, in the order asked, on one thread; read and the setters wait for theirs: never on the main
 * thread.
 */
internal object Rokid {
    private const val SERVICE = "rokid_display"
    private const val DESCRIPTOR = "com.rokid.display.IRokidDisplayManager"
    /** IRokidDisplayManager's transaction codes. */
    private const val SET_MODE = 4
    private const val GET_MODE = 6
    private const val PLUGGED = 7
    private const val SET_AUDIO = 12
    private const val GET_AUDIO = 13
    private const val GET_FPS = 14
    private const val SET_FPS = 15
    private const val SET_SCENE = 16
    private const val GET_SCENE = 17
    /** Rokid's USB vendor id: the glasses' own USB (sensors and audio), there while they are. */
    private const val GLASSES_VENDOR = 0x04D2
    /** Display modes as getAirDisplayMode reports them; 3 is 2D at 120 Hz, which is what setting 120 Hz reads back as. */
    const val MODE_2D = 0
    const val MODE_3D = 1
    const val MODE_2D_120 = 3
    /** Refresh rates the Max takes (it refuses 90). */
    @JvmField val FPS = intArrayOf(60, 120)
    /** Picture scenes: standard, movie, reading, eye care. */
    @JvmField val SCENES = intArrayOf(1, 2, 3, 4)
    /** Speaker mode: private (quieter, so others hear less) or public. */
    @JvmField val AUDIO = intArrayOf(0, 1)
    /**
     * Sliders that Rokid's quick menu (assistserver's ShortcutMenuService) shows over any app: the glasses' brightness
     * (not in rokid_display) and the display zoom. Rokid's Settings opens them with these broadcasts; the receiver
     * takes them with no permission.
     */
    const val BRIGHTNESS = "android.intent.action.displayBrightness.MENU"
    const val ZOOM = "com.rokid.erbox.action.displayZoom"
    private const val ASSIST = "com.rokid.glass.er.assistserver"
    /** `service call`'s answer: the exception code (0 = none), then the int the call returned. */
    private val PARCEL = Pattern.compile("Result: Parcel\\(([0-9a-f]{8}) ([0-9a-f]{8})")

    class State {
        @JvmField var mode = 0
        @JvmField var fps = 0
        @JvmField var scene = 0
        @JvmField var audio = 0

        fun is3d() = mode == MODE_3D
    }

    private fun interface Calls {
        fun call(code: Int, arg: Int?): Int
    }

    private val WORK = Executors.newSingleThreadExecutor()
    private var binder: IBinder? = null
    private var binderTried = false

    @JvmStatic
    fun available() = HomeMode.ROKID

    /** The USB events glassesEvent reads. */
    @JvmStatic
    fun glassesFilter() = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    /**
     * True when the glasses came onto USB or left it. Waking the box takes them off USB for about five seconds (the
     * HDMI link retrains), so a read made as the home screen comes back finds no glasses: their return is when to read
     * again.
     */
    @JvmStatic
    fun glassesEvent(i: Intent?): Boolean {
        @Suppress("DEPRECATION")
        val d: UsbDevice? = i?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        return d?.vendorId == GLASSES_VENDOR
    }

    /** The glasses' settings now, or null when no glasses are plugged in or the service can't be reached. */
    @JvmStatic
    fun read(c: Context): State? {
        if (!available()) return null
        return withCalls(c) { calls -> if (calls.call(PLUGGED, null) == 0) null else readAll(calls) }
    }

    /** Opens one of Rokid's sliders (BRIGHTNESS, ZOOM). False: the broadcast couldn't be sent. */
    @JvmStatic
    fun openSlider(c: Context, action: String?): Boolean {
        return try {
            c.sendBroadcast(Intent(action).setPackage(ASSIST))
            true
        } catch (e: RuntimeException) {
            AppLog.w("glasses: couldn't open " + action + ": " + e.message)
            false
        }
    }

    /** The glasses' display mode, or -1 when no glasses are plugged in or the service can't be reached. */
    @JvmStatic
    fun mode(c: Context): Int {
        if (!available()) return -1
        val mode = withCalls(c) { calls -> if (calls.call(PLUGGED, null) == 0) -1 else calls.call(GET_MODE, null) }
        return mode ?: -1
    }

    @JvmStatic
    fun setMode(c: Context, mode: Int) = set(c, SET_MODE, mode)

    @JvmStatic
    fun setFps(c: Context, fps: Int) = set(c, SET_FPS, fps)

    @JvmStatic
    fun setScene(c: Context, scene: Int) = set(c, SET_SCENE, scene)

    @JvmStatic
    fun setAudio(c: Context, audio: Int) = set(c, SET_AUDIO, audio)

    /**
     * The home screen is back: glasses left in 3D go back to 2D, as Rokid's own launcher does (a 3D player leaves them
     * in 3D, and our home screen isn't made for it). Nothing is set when no glasses are plugged in. Doesn't wait; a
     * read asked after it (the drawer's 2D/3D button) gets the mode it leaves.
     */
    @JvmStatic
    fun homeResumed(c: Context) {
        if (!available()) return
        val app = c.applicationContext
        WORK.execute {
            runCalls<Any?>(app) { calls ->   // typed: a lambda that gives Nothing? compiles to a class (R8 Info)
                if (calls.call(PLUGGED, null) == 0 || calls.call(GET_MODE, null) != MODE_3D) return@runCalls null
                AppLog.w("glasses: back to 2D on the home screen ("
                    + (if (calls.call(SET_MODE, MODE_2D) == 1) "done" else "refused") + ")")
                null
            }
        }
    }

    /**
     * Changes one setting when glasses are plugged in, then reads them all back (120 Hz changes the mode too). A new
     * mode or refresh rate retrains the HDMI link: the glasses read as unplugged for about two seconds, and what's read
     * meanwhile is stale, so it waits (up to 8 s) for them to come back with what was asked.
     */
    private fun set(c: Context, code: Int, value: Int): State? {
        if (!available()) return null
        return withCalls(c) { calls ->
            if (calls.call(PLUGGED, null) == 0) return@withCalls null
            val ok = calls.call(code, value)
            AppLog.w("glasses: call $code = $value" + (if (ok == 1) "" else " refused"))
            val display = code == SET_MODE || code == SET_FPS
            val until = SystemClock.elapsedRealtime() + (if (ok == 1 && display) 8000 else 0)
            do {
                SystemClock.sleep(if (display) 500L else 300L)
            } while (SystemClock.elapsedRealtime() < until && !settled(calls, code, value))
            readAll(calls)
        }
    }

    /** The glasses are back on the link with the mode or refresh rate asked for (2D: either refresh rate). */
    private fun settled(calls: Calls, code: Int, value: Int): Boolean {
        if (calls.call(PLUGGED, null) == 0) return false
        if (code == SET_FPS) return calls.call(GET_FPS, null) == value
        val mode = calls.call(GET_MODE, null)
        return if (value == MODE_3D) mode == MODE_3D else mode != MODE_3D
    }

    private fun readAll(calls: Calls): State {
        val s = State()
        s.mode = calls.call(GET_MODE, null)
        s.fps = calls.call(GET_FPS, null)
        s.scene = calls.call(GET_SCENE, null)
        s.audio = calls.call(GET_AUDIO, null)
        return s
    }

    /** Runs `work` on the calls' thread and waits for it. Null when any of it fails. */
    private fun <T> withCalls(c: Context, work: (Calls) -> T): T? {
        val app = c.applicationContext
        return try {
            WORK.submit(Callable { runCalls(app, work) }).get()
        } catch (e: ExecutionException) {
            AppLog.w("glasses: " + e.cause)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Runs `work` over the binder, or else over one ADB connection. Null when any of it fails. */
    private fun <T> runCalls(app: Context, work: (Calls) -> T): T? {
        val b = binder()
        try {
            if (b != null) return work(Calls { code, arg -> transact(b, code, arg) })
            Adb.connect(app, 10_000, null).use { adb ->
                return work(Calls { code, arg -> shell(adb, code, arg) })
            }
        } catch (e: Exception) {   // IOException or RuntimeException (nothing else is thrown here)
            AppLog.w("glasses: " + e.message)
            return null
        }
    }

    /**
     * Looked up once per process (on the calls' thread), and again after the service restarts (its old binder dies);
     * without access it stays ADB. The log says which way the calls go.
     */
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
        AppLog.w("glasses: rokid_display "
            + (if (binder != null) "over the binder" else "over ADB (the app can't look it up)"))
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

    private fun shell(adb: Adb, code: Int, arg: Int?): Int {
        val reply = adb.shell("service call $SERVICE $code" + (if (arg == null) "" else " i32 $arg"))
        val m = PARCEL.matcher(reply)
        if (!m.find() || java.lang.Long.parseLong(m.group(1), 16) != 0L) {
            throw IOException("rokid_display call " + code + ": " + reply.trim { it <= ' ' })
        }
        return java.lang.Long.parseLong(m.group(2), 16).toInt()
    }
}
