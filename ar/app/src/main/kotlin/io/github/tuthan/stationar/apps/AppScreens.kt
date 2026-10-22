package io.github.tuthan.stationar.apps

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.adb.AdbClient
import io.github.tuthan.stationar.core.helper.Command
import io.github.tuthan.stationar.core.keys.KeyFilter
import io.github.tuthan.stationar.helper.Helper
import io.github.tuthan.stationar.keys.KeyFilterSetting
import io.github.tuthan.stationar.keys.KeyRouter
import io.github.tuthan.stationar.sources.VirtualDisplaySource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Android apps in AR's screens: their virtual displays, the shell helper that starts them there, and the remote,
 * which follows your gaze. When you settle on an app's screen, the helper moves the focus to its display and the
 * remote drives the app directly; on any other screen the focus is back on AR (display 0). The key filter keeps Menu,
 * held OK and held Back for AR while an app has the remote.
 *
 * Main thread, except where noted; helper calls run on one worker.
 */
class AppScreens(private val context: Context, private val listener: Listener) : VirtualDisplaySource.Listener {
    interface Listener {
        /** A line for the HUD (the ADB prompt, an app that didn't start), or null to clear it. Main thread. */
        fun onAppStatus(text: String?)

        /** A reserved key, kept from the app. Main thread. */
        fun onKeyAction(action: KeyFilter.Action)
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val helper = Helper(context)
    private val sources = ConcurrentHashMap<String, VirtualDisplaySource>()
    private val filter = KeyFilter()
    @Volatile private var closed = false
    /** The source the gaze settled on (null: not an app), and the display that has the focus now (worker). */
    @Volatile private var wanted: String? = null
    @Volatile private var focusedDisplay = 0
    /** AR's own panel is open: the focus stays on AR whatever you look at. */
    private var holding = false
    private var filterOn = false

    /** The source for screen `name` (`app:<package>`), made once (GL thread, from the renderer). */
    fun source(name: String): VirtualDisplaySource = sources.getOrPut(name) { VirtualDisplaySource(name, context, this) }

    /** App screens that exist now. */
    val count: Int get() = sources.values.count { it.displayId >= 0 }

    // MARK: - Displays (GL thread)

    override fun onDisplay(source: VirtualDisplaySource, displayId: Int) {
        val pkg = source.name.removePrefix("app:")
        work.execute {
            if (closed) return@execute
            if (!ensureHelper()) return@execute
            val component = launchComponent(pkg)
            if (component == null) {
                status("${label(pkg)} has no TV screen to open")
                return@execute
            }
            val reply = helper.call(Command.Launch(displayId, component))
            if (reply == null) {
                status("${label(pkg)} didn't open in its screen")
                return@execute
            }
            source.task = reply.trim().toIntOrNull() ?: -1
            AppLog.i("${source.name}: started on display $displayId, task ${source.task}")
            status(null)
            if (wanted == source.name) focusNow()
        }
    }

    override fun onDisplayGone(source: VirtualDisplaySource, displayId: Int) {
        // On the GL thread, blocking: the app's task must close before its display goes, or it lands on the glasses.
        val t = source.task
        if (t >= 0 && helper.running) helper.call(Command.RemoveTask(t))
        if (focusedDisplay == displayId) focusedDisplay = -1
    }

    // MARK: - Focus follows the gaze

    /** The gaze settled on screen `name` (null or not an app: AR keeps the remote). */
    fun gazeOn(name: String?) {
        wanted = if (name != null && sources.containsKey(name)) name else null
        work.execute { focusNow() }
    }

    /** AR's panel opened (`true`): the remote stays with AR until it closes. */
    fun holdForPanel(hold: Boolean) {
        holding = hold
        work.execute { focusNow() }
    }

    /** Worker: moves the focus where it should be now. */
    private fun focusNow() {
        if (closed || !helper.running) return
        val src = if (holding) null else wanted?.let { sources[it] }
        val display = if (src != null && src.task >= 0 && src.displayId >= 0) src.displayId else 0
        if (display == focusedDisplay) return
        // A corner of the display: the touch is cancelled at once, and corners hold the least.
        val ok = if (display == 0) helper.call(Command.Focus(0, 1, 1)) != null
            else helper.call(Command.Focus(display, VirtualDisplaySource.WIDTH - 2, VirtualDisplaySource.HEIGHT - 2)) != null
        if (!ok) return
        focusedDisplay = display
        AppLog.i("keys: the remote drives display $display")
        main.post { filter.live = display != 0 }
    }

    // MARK: - The helper and the key filter (worker)

    private fun ensureHelper(): Boolean {
        if (helper.running) return true
        try {
            helper.start { status("On the Station: allow USB debugging for Station AR (tick Always allow)") }
            focusedDisplay = 0
        } catch (e: IOException) {
            AppLog.w("helper: couldn't start", e)
            status("App screens need the Station's USB debugging: ${e.message}")
            return false
        }
        setFilter(true)
        main.post { KeyRouter.handler = keyHandler }
        return true
    }

    private fun setFilter(on: Boolean) {
        if (filterOn == on) return
        try {
            AdbClient.connect(context, 60_000, null).use { KeyFilterSetting.set(it, on) }
            filterOn = on
            AppLog.i("keys: filter " + if (on) "on" else "off")
        } catch (e: Exception) {
            AppLog.w("keys: couldn't turn the filter " + if (on) "on" else "off", e)
        }
    }

    // MARK: - Keys (main thread, from KeyFilterService)

    private val keyHandler = KeyRouter.Handler { e ->
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return@Handler false
        val injected = e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
        apply(filter.onKey(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.eventTime, injected))
    }

    private val timeout = Runnable { apply(filter.onTimeout(SystemClock.uptimeMillis())) }

    private fun apply(r: KeyFilter.Result): Boolean {
        r.action?.let { listener.onKeyAction(it) }
        if (r.resend != 0) {
            val code = r.resend
            work.execute { helper.call(Command.Key(code)) }
        }
        if (r.checkAt != 0L) {
            main.removeCallbacks(timeout)
            main.postAtTime(timeout, r.checkAt)
        }
        return r.consume
    }

    // MARK: - Apps

    /** The activity a TV launcher would open for `pkg`, as `pkg/class`. */
    private fun launchComponent(pkg: String): String? {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        return intent.component?.flattenToString()
    }

    private fun label(pkg: String): String = try {
        context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun status(text: String?) = main.post { if (!closed) listener.onAppStatus(text) }

    // MARK: - The end

    /**
     * Leaving AR: closes every app's task, releases the displays, takes the key filter out and stops the helper. Safe
     * to call twice, and from any thread; the work runs on the worker.
     */
    fun close() {
        if (closed) return
        closed = true
        KeyRouter.handler = null
        filter.live = false
        main.removeCallbacks(timeout)
        work.execute {
            for (s in sources.values) {
                val t = s.task
                if (t >= 0 && helper.running) helper.call(Command.RemoveTask(t))
                s.release()
            }
            if (helper.running) helper.call(Command.Focus(0, 1, 1))
            helper.stop()
            setFilter(false)
        }
        work.shutdown()
    }

    companion object {
        /** A key filter entry left by a crash: taken out at the next start (worker thread; needs the ADB key). */
        fun cleanLeftover(c: Context) {
            if (!KeyFilterSetting.isOn(c)) return
            try {
                AdbClient.connect(c, 10_000, null).use { KeyFilterSetting.set(it, false) }
                AppLog.i("keys: removed a filter entry left from before")
            } catch (e: Exception) {
                AppLog.w("keys: couldn't remove the leftover filter entry", e)
            }
        }

        /** Apps a TV launcher lists: (package, label), without Station AR and Station Home. */
        fun launchable(c: Context): List<Pair<String, String>> {
            val pm = c.packageManager
            val seen = HashSet<String>()
            val out = ArrayList<Pair<String, String>>()
            for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == c.packageName || pkg.startsWith("io.github.tuthan.beamstation") || !seen.add(pkg)) continue
                    if (category == Intent.CATEGORY_LAUNCHER &&
                        (ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    out.add(pkg to ri.loadLabel(pm).toString())
                }
            }
            return out
        }
    }
}
