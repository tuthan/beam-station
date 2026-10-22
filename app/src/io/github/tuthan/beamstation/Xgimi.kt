package io.github.tuthan.beamstation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.tv.TvContract
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * XGIMI projectors: auto focus, auto keystone, speaker mode and HDMI through XGIMI's own services (exported, with no
 * permission), the Play 6's battery, eco mode, corrections after a move and HDMI game mode, and the system language. XGIMI's screens are Chinese only, and its Settings switches the system to
 * Chinese each time it opens, with a notice whose OK closes the panel. So in our mode the system language
 * follows the app's: Chinese just before XGIMI's panel opens (no notice), ours again when our windows get focus back.
 */
internal object Xgimi {
    /** XGIMI SystemUI's focus and keystone request; `type` 8 focuses, 10 squares the picture. */
    private const val AF_AK = "com.xgimi.systemui.action.AF_AK"
    private const val SYSTEM_UI = "com.xgimi.systemui"
    private const val FOCUS = 8
    private const val KEYSTONE = 10
    private val SPEAKER = ComponentName("com.xgimi.soundermodeservice",
        "com.xgimi.soundermodeservice.SounderModeService")
    /** IGimiSound's calls (1 and 2); 3 is what XGIMI's own button passes to turn speaker mode on, 0 turns it off. */
    private const val SOUND = "com.xgimi.aidl.IGimiSound"
    private const val GET_MODE = IBinder.FIRST_CALL_TRANSACTION
    private const val SET_MODE = GET_MODE + 1
    private const val SPEAKER_ON = 3
    private val SETTINGS = ComponentName("com.android.newsettings",
        "com.xgimi.newsettings.SettingService")
    /** How long XGIMI's panel has to show up (and take focus) after the system switches to Chinese for it. */
    private const val LEND_MS = 3000L

    private val WORK = Executors.newSingleThreadExecutor()
    /** The stock home check (ADB), apart from WORK so the language never waits on it. */
    private val CHECK = Executors.newSingleThreadExecutor()
    private const val HOME_CHECK_MS = 60_000L
    private val MAIN = Handler(Looper.getMainLooper())
    @Volatile private var focused = false
    @Volatile private var lent = false
    @Volatile private var verifyAsked = false
    @Volatile private var lentAt = 0L
    @Volatile private var homeCheckedAt = 0L

    @JvmStatic
    fun hasFocusKeystone(c: Context) = HomeMode.XGIMI && c.packageManager.resolveService(afAk(0), 0) != null

    @JvmStatic
    fun hasSpeaker(c: Context) =
        HomeMode.XGIMI && c.packageManager.resolveService(Intent().setComponent(SPEAKER), 0) != null

    @JvmStatic
    fun autoFocus(c: Context) = start(c, afAk(FOCUS))

    @JvmStatic
    fun autoKeystone(c: Context) = start(c, afAk(KEYSTONE))

    private fun afAk(type: Int): Intent = Intent(AF_AK).setPackage(SYSTEM_UI).putExtra("type", type)

    /**
     * An HDMI input in XGIMI's player (com.xgimi.xhplayer), as XGIMI Home opens it: Android's own request for the input
     * goes to Live Channels. The type is given, so the match doesn't wait on the TV provider.
     */
    @JvmStatic
    fun hdmi(inputId: String?): Intent = Intent("com.xgimi.action.hdmiPlayer")
        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), TvContract.Channels.CONTENT_ITEM_TYPE)

    private fun start(c: Context, service: Intent): Boolean {
        return try {
            c.startService(service) != null
        } catch (e: IllegalStateException) {
            AppLog.w("XGIMI focus and keystone: " + e.message)
            false
        } catch (e: SecurityException) {
            AppLog.w("XGIMI focus and keystone: " + e.message)
            false
        }
    }

    /**
     * Speaker mode on or off (the projector plays a phone's Bluetooth audio). `done` gets, on the main thread, whether
     * it is on now, or null when it didn't change (XGIMI refuses it while casting, for one). False: the service isn't there.
     */
    @JvmStatic
    fun toggleSpeaker(c: Context, done: Consumer<Boolean?>): Boolean {
        val app = c.applicationContext
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val self: ServiceConnection = this
                WORK.execute {
                    var on: Boolean? = null
                    try {
                        val was = mode(binder!!) != 0
                        setMode(binder, if (was) 0 else SPEAKER_ON)
                        SystemClock.sleep(1500)   // XGIMI applies it on its own thread
                        val now = mode(binder) != 0
                        AppLog.w("speaker mode " + (if (now) "on" else "off"))
                        if (now != was) on = now
                    } catch (e: Exception) {   // RemoteException or RuntimeException
                        AppLog.w("speaker mode: $e")
                    }
                    try {
                        app.unbindService(self)
                    } catch (ignored: IllegalArgumentException) {
                    }
                    val result = on
                    MAIN.post { done.accept(result) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            if (app.bindService(Intent("xgimi.service.SOUNDERMODESERVICE").setComponent(SPEAKER), conn,
                    Context.BIND_AUTO_CREATE)) {
                return true
            }
            app.unbindService(conn)
        } catch (e: SecurityException) {
            AppLog.w("speaker mode: " + e.message)
        } catch (e: IllegalArgumentException) {
            AppLog.w("speaker mode: " + e.message)
        }
        return false
    }

    // MARK: - Battery and eco mode

    /**
     * The Play 6's battery, from XGIMI's platform library (com.xgimi.api, on the boot class path, so this app can call
     * it as XGIMI's own apps do). Android's battery service doesn't know this battery: it says there is none.
     */
    class Battery {
        @JvmField var level = 0
        @JvmField var adapter = false
        @JvmField var full = false
    }

    /** XGIMI's Play 6 (code name dayao): the model whose battery and eco mode were checked. */
    @JvmField val PLAY_6 = HomeMode.XGIMI && "Product_dayao" == android.os.Build.PRODUCT

    /** The battery now, or null where there is none to read. Blocks briefly: not on the UI thread. */
    @JvmStatic
    fun battery(): Battery? {
        if (!PLAY_6) return null
        return platformCall("battery") {
            val power = platform("com.xgimi.gmpf.api.PowerManager")
            val b = Battery()
            b.level = (call(power, "getBatteryLevel") as Number).toInt()
            b.adapter = call(power, "isAdapterPowered") as Boolean
            b.full = call(power, "isBatteryChargedFull") as Boolean
            if (b.level >= 0 && b.level <= 100) b else null
        }
    }

    @JvmStatic
    fun hasEco() = PLAY_6

    /**
     * Eco mode on or off: lower brightness and sound for a longer battery, as XGIMI's Settings sets it. `done` gets,
     * on the main thread, whether it is on now, or null when it didn't change.
     */
    @JvmStatic
    fun toggleEco(done: Consumer<Boolean?>) {
        WORK.execute {
            val on = platformCall("eco mode") {
                val system = platform("com.xgimi.gmpf.api.SystemManager")
                val was = call(system, "getEcoState") as Boolean
                system.javaClass.getMethod("setEcoState", Boolean::class.java).invoke(system, !was)
                val now = call(system, "getEcoState") as Boolean
                AppLog.w("eco mode " + (if (now) "on" else "off"))
                if (now != was) now else null
            }
            MAIN.post { done.accept(on) }
        }
    }

    // MARK: - When the projector moves

    /**
     * What the Play 6 does when it's moved: square the picture again (XGIMI's "real-time picture correction") and
     * focus it again ("smart auto focus"). XGIMI's own calibration screens turn the first off while they run.
     */
    class Moves {
        @JvmField var keystone = false
        @JvmField var focus = false
    }

    /** Now, or null where it can't be read. Blocks briefly: not on the UI thread. */
    @JvmStatic
    fun moves(): Moves? {
        if (!PLAY_6) return null
        return platformCall("moves") {
            val motion = platform("com.xgimi.gmpf.api.MotionDetectionManager")
            val m = Moves()
            m.keystone = call(motion, "getAccTriggerAK") as Boolean
            m.focus = call(motion, "getAngTriggerAF") as Boolean
            m
        }
    }

    /** Keystone after a move, on or off. On, the picture is also squared now (XGIMI starts from a reset picture). */
    @JvmStatic
    fun setMoveKeystone(c: Context, on: Boolean): Moves? {
        platformCall("keystone when moved") {
            val motion = platform("com.xgimi.gmpf.api.MotionDetectionManager")
            motion.javaClass.getMethod("setAccTriggerAK", Boolean::class.java).invoke(motion, on)
            AppLog.w("keystone when moved " + (if (on) "on" else "off"))
            if (on) autoKeystone(c)
        }
        return moves()
    }

    /** Focus after a move, on or off: the two switches XGIMI's "smart auto focus" sets together. */
    @JvmStatic
    fun setMoveFocus(on: Boolean): Moves? {
        platformCall("focus when moved") {
            val motion = platform("com.xgimi.gmpf.api.MotionDetectionManager")
            motion.javaClass.getMethod("setAngTriggerAF", Boolean::class.java).invoke(motion, on)
            val focus = platform("com.xgimi.gmpf.api.ProjectorFocusManager")
            focus.javaClass.getMethod("setPowerOnAFUseDistance", Boolean::class.java).invoke(focus, on)
            AppLog.w("focus when moved " + (if (on) "on" else "off"))
        }
        return moves()
    }

    /** HDMI game mode, as XGIMI's HDMI menu sets it; auto turns it on when XGIMI recognizes a console. */
    const val GAME_OFF = 0
    const val GAME_ON = 1
    const val GAME_AUTO = 2

    /** Now, or -1 where it can't be read: XGIMI keeps a type (1: auto) and a state (0: on, 1: off). Not on the UI thread. */
    @JvmStatic
    fun gameMode(): Int {
        if (!PLAY_6) return -1
        return platformCall("game mode") {
            val display = platform("com.xgimi.gmpf.api.DisplayManager")
            val prop = Class.forName("com.xgimi.gmpf.rp.GameModeProp").getConstructor().newInstance()
            if (!(display.javaClass.getMethod("getGameModeProp", prop.javaClass).invoke(display, prop) as Boolean)) {
                return -1
            }
            val type = prop.javaClass.getField("type").getInt(prop)
            val state = prop.javaClass.getField("state").getInt(prop)
            if (type == 1) GAME_AUTO else if (state == 0) GAME_ON else GAME_OFF
        } ?: -1
    }

    /** The calls XGIMI's own menu makes (turning it on applies the latency option again); returns what it reads after. */
    @JvmStatic
    fun setGameMode(mode: Int): Int {
        platformCall("game mode") {
            val display = platform("com.xgimi.gmpf.api.DisplayManager")
            val type: Method = display.javaClass.getMethod("setGameModeType", Int::class.java)
            val state: Method = display.javaClass.getMethod("setGameModeState", Int::class.java)
            if (mode == GAME_AUTO) {
                type.invoke(display, 1)
            } else {
                if (mode == GAME_ON) {
                    display.javaClass.getMethod("setGameModeOption", Int::class.java)
                        .invoke(display, call(display, "getGameModeOption"))
                }
                type.invoke(display, 0)
                state.invoke(display, if (mode == GAME_ON) 0 else 1)
            }
            AppLog.w("game mode $mode")
        }
        return gameMode()
    }

    /**
     * Runs `run` against XGIMI's platform library: its result, or null (logged after `what`) when any of it fails.
     * Exception stands for ReflectiveOperationException and RuntimeException (nothing else checked is thrown there);
     * LinkageError (a missing class or method) is caught on its own, other errors are not.
     */
    private inline fun <T> platformCall(what: String, run: () -> T): T? {
        try {
            return run()
        } catch (e: Exception) {
            AppLog.w("$what: $e")
        } catch (e: LinkageError) {
            AppLog.w("$what: $e")
        }
        return null
    }

    private fun platform(manager: String): Any =
        Class.forName(manager).getMethod("getInstance").invoke(null)!!

    private fun call(o: Any, getter: String): Any? = o.javaClass.getMethod(getter).invoke(o)

    private fun mode(b: IBinder): Int {
        val input = Parcel.obtain()
        val out = Parcel.obtain()
        try {
            input.writeInterfaceToken(SOUND)
            b.transact(GET_MODE, input, out, 0)
            out.readException()
            return out.readInt()
        } finally {
            input.recycle()
            out.recycle()
        }
    }

    private fun setMode(b: IBinder, mode: Int) {
        val input = Parcel.obtain()
        val out = Parcel.obtain()
        try {
            input.writeInterfaceToken(SOUND)
            input.writeInt(mode)
            b.transact(SET_MODE, input, out, 0)
            out.readException()
        } finally {
            input.recycle()
            out.recycle()
        }
    }

    // MARK: - Settings and the system language

    /** XGIMI's Settings panel (a service the remote's key starts), opened in Chinese so it has nothing to switch. */
    @JvmStatic
    fun openSettings(c: Context): Boolean {
        val app = c.applicationContext
        val panel = Intent("com.xgimi.settings.SETTINGS").setComponent(SETTINGS)
        if (app.packageManager.resolveService(panel, 0) == null) return false
        WORK.execute {
            if (canKeep(app) && Locale.SIMPLIFIED_CHINESE != systemLocale() && setLocale(Locale.SIMPLIFIED_CHINESE)) {
                lent = true
                lentAt = SystemClock.elapsedRealtime()
                // The panel didn't take focus (it didn't open, or closed at once): take the language back anyway.
                MAIN.postDelayed({
                    if (focused && lent) keepLanguage(app)
                }, LEND_MS + 500)
            }
            try {
                app.startForegroundService(panel)
            } catch (e: IllegalStateException) {
                AppLog.w("couldn't open XGIMI's settings: " + e.message)
            } catch (e: SecurityException) {
                AppLog.w("couldn't open XGIMI's settings: " + e.message)
            }
        }
        return true
    }

    /**
     * One of our windows (home screen, settings) got or lost focus. Back from the panel we opened in Chinese, the
     * language is ours again. Not when XGIMI switched it itself (the remote's Settings key): its notice closes the
     * panel, and taking the language back would bring the notice back on every press.
     */
    @JvmStatic
    fun focusChanged(c: Context, hasFocus: Boolean) {
        focused = hasFocus
        if (!HomeMode.XGIMI || !hasFocus) return
        if (!lent) heal(c.applicationContext)
        else if (SystemClock.elapsedRealtime() - lentAt >= LEND_MS) keepLanguage(c)
        homeCheck(c.applicationContext)
    }

    /** XGIMI Home stays off (HomeMode.xgimiHomeCheck): at most once a minute, when our windows get focus. */
    private fun homeCheck(app: Context) {
        val now = SystemClock.elapsedRealtime()
        if (homeCheckedAt != 0L && now - homeCheckedAt < HOME_CHECK_MS) return
        homeCheckedAt = now
        CHECK.execute { HomeMode.xgimiHomeCheck(app) }
    }

    /** One of our screens goes to the background (an app is opening): the app gets our language. */
    @JvmStatic
    fun paused(c: Context) {
        if (HomeMode.XGIMI) keepLanguage(c)
    }

    private fun keepLanguage(c: Context) {
        val app = c.applicationContext
        WORK.execute {
            if (!canKeep(app)) {
                heal(app)
                return@execute
            }
            lent = false
            val want = Ui.locale()
            val now = systemLocale()
            if (now != null && now.language != want.language) setLocale(want)
        }
    }

    /**
     * XGIMI force-stops apps a few seconds after they leave the screen, so the check after an update or a boot may
     * never have run, and the permissions it grants may be missing. Asked once per process, in our mode.
     */
    private fun heal(app: Context) {
        if (verifyAsked) return
        WORK.execute {
            val all = granted(app) &&
                app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (verifyAsked || all || HomeMode.FREE != HomeMode.state(app).optString("mode")) return@execute
            verifyAsked = true
            HomeMode.verify(app)
        }
    }

    /** Only in our mode, once the switch has let this app change the system configuration (HomeMode). */
    private fun canKeep(app: Context) =
        HomeMode.XGIMI && granted(app) && HomeMode.FREE == HomeMode.state(app).optString("mode")

    private fun granted(app: Context) =
        app.checkSelfPermission(Manifest.permission.CHANGE_CONFIGURATION) == PackageManager.PERMISSION_GRANTED &&
            Settings.System.canWrite(app)

    private fun activityManager(): Any? =
        Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)

    private fun systemLocale(): Locale? {
        return try {
            val am = activityManager()
            val config = Class.forName("android.app.IActivityManager")
                .getMethod("getConfiguration").invoke(am) as Configuration
            if (config.locales.isEmpty) null else config.locales.get(0)
        } catch (e: Exception) {   // ReflectiveOperationException or RuntimeException
            AppLog.w("couldn't read the system language: $e")
            null
        }
    }

    /** The system language, as Settings sets it (Android has no public call for it). */
    private fun setLocale(locale: Locale): Boolean {
        return try {
            val am = activityManager()
            val iam = Class.forName("android.app.IActivityManager")
            val config = iam.getMethod("getConfiguration").invoke(am) as Configuration
            config.setLocales(LocaleList(locale))
            Configuration::class.java.getField("userSetLocale").setBoolean(config, true)
            iam.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(am, config)
            AppLog.w("system language: " + locale.toLanguageTag())
            true
        } catch (e: Exception) {   // ReflectiveOperationException or RuntimeException
            AppLog.w("couldn't set the system language: $e")
            false
        }
    }
}
