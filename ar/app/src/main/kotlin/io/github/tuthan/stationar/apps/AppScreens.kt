package io.github.tuthan.stationar.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.StationAr
import io.github.tuthan.stationar.core.compat.AppCompat
import io.github.tuthan.stationar.core.helper.Command
import io.github.tuthan.stationar.core.helper.TaskInfo
import io.github.tuthan.stationar.core.keys.KeyFilter
import io.github.tuthan.stationar.helper.Helper
import io.github.tuthan.stationar.sources.VirtualDisplaySource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android apps in AR's screens: their virtual displays, the shell helper that starts them there, and the remote,
 * which follows your gaze. When you settle on an app's screen, the helper moves the focus to its display and the
 * remote drives the app; on any other screen the focus is back on AR (display 0). The Station stamps its buttons' keys
 * for the glasses' display whatever has the focus, so they reach AR's window ([onKey]) and are passed on to the app
 * through the helper; the [KeyFilter] keeps Menu, held OK and held Back for AR meanwhile.
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
    private val closed = AtomicBoolean(false)
    /** The source the gaze settled on (null: not an app), and the display that has the focus now (worker). */
    @Volatile private var wanted: String? = null
    @Volatile private var focusedDisplay = 0
    /** AR's own panel is open: the focus stays on AR whatever you look at. */
    private var holding = false

    /** The glasses' display, which every app screen copies. */
    private val size by lazy { VirtualDisplaySource.glassesSize(context) }

    /** The source for screen `name` (`app:<package>`), made once (GL thread, from the renderer). */
    fun source(name: String): VirtualDisplaySource {
        val pkg = name.removePrefix("app:")
        return sources.getOrPut(name) {
            VirtualDisplaySource(name, context, this, size).also {
                it.label = StationAr.appName(context, pkg)
                it.icon = StationAr.appIcon(context, pkg)
            }
        }
    }

    /** App screens that exist now. */
    val count: Int get() = sources.values.count { it.displayId >= 0 }

    /** Tasks to move into the next screen made for their app (by screen name), instead of starting the app there. */
    private val adopting = ConcurrentHashMap<String, Int>()

    /**
     * AR was covered while app screens are up (worker; `done(moved, other)` then runs on the main thread). An app in a
     * screen may have opened an activity of its own on the glasses' display: VLC starts its player from its service, as
     * a new task, and Android puts that on display 0. Such tasks are moved into their app's screen (`moved`). Another
     * app on top of display 0 that could show in a screen (an AirPlay receiver's player, say) is `other`: AR is brought
     * back to ask. Either way AR is brought back to the front; the home screen on top of display 0 is neither, and AR
     * ends.
     */
    fun reclaim(done: (Boolean, TaskInfo?) -> Unit) {
        val queued = background {
            var moved = false
            var other: TaskInfo? = null
            if (!closed.get() && helper.running) {
                val screens = sources.values.filter { it.displayId >= 0 }.associateBy { it.name.removePrefix("app:") }
                val tasks = if (screens.isEmpty()) emptyList() else TaskInfo.decodeAll(helper.call(Command.Tasks) ?: "")
                for (t in tasks) {
                    val src = screens[t.pkg] ?: continue
                    if (t.display != 0) continue
                    if (helper.call(Command.MoveTask(t.id, src.displayId)) == null) continue
                    AppLog.i("${src.name}: its task ${t.id} opened on the glasses' display; moved into its screen")
                    // Its app had closed there: this is its app now.
                    if (src.appClosed) opened(src, t.id)
                    moved = true
                }
                // Root tasks come top first: the first one left showing on display 0 covers AR.
                val top = tasks.firstOrNull { it.display == 0 && it.visible && it.pkg !in screens }
                if (top != null && offerable(top.pkg)) {
                    AppLog.i("apps: ${top.pkg} (task ${top.id}) opened over AR")
                    other = top
                }
                if (moved || other != null) {
                    helper.call(Command.Front)
                    focusedDisplay = -1
                    focusNow()
                }
            }
            main.post { done(moved, other) }
        }
        if (!queued) done(false, null)
    }

    /** The next screen made for `pkg` takes its task `task` from the glasses' display, instead of starting the app. */
    fun adopt(pkg: String, task: Int) {
        adopting["app:$pkg"] = task
    }

    /** Whether AR offers to show `pkg`'s task in a screen: an app with a launcher entry that works in one. */
    private fun offerable(pkg: String): Boolean {
        if (pkg == context.packageName || pkg == homePackage || pkg == "android" || pkg == "com.android.systemui") return false
        val status = AppCompat.of(pkg).status
        if (status == AppCompat.Status.DRM || status == AppCompat.Status.NO_VIDEO) return false
        return launchComponent(pkg) != null
    }

    // MARK: - Watching full screen

    /** An app moved onto the glasses' display to play full screen: its task, its screen, its top activity then. */
    private class Watch(val source: VirtualDisplaySource, val task: Int, val top: String)

    @Volatile private var watch: Watch? = null

    /** An app is on the glasses' display, full screen, and AR waits behind it. */
    val watching: Boolean get() = watch != null

    /**
     * Puts the top task of screen `name` on the glasses' display, full screen (worker; `done(ok)` on the main thread):
     * a film there goes straight to the display, where in a screen its every frame costs a GPU pass. AR rests behind
     * it; [followWatch] brings it back.
     */
    fun watchFullScreen(name: String, done: (Boolean) -> Unit) {
        val queued = background {
            val src = sources[name]
            var ok = false
            if (!closed.get() && helper.running && src != null && src.displayId >= 0) {
                val top = TaskInfo.decodeAll(helper.call(Command.Tasks) ?: "").firstOrNull { it.display == src.displayId }
                if (top != null) {
                    // Set first: the move stops AR, and AR asks whether it's watching.
                    watch = Watch(src, top.id, top.top)
                    ok = helper.call(Command.MoveTask(top.id, 0)) != null
                    if (ok) AppLog.i("${src.name}: task ${top.id} full screen (${top.top})") else watch = null
                }
            }
            main.post { done(ok) }
        }
        if (!queued) done(false)
    }

    /**
     * AR rests behind an app watched full screen: the helper looks at the glasses' display every [WATCH_POLL_MS].
     * The home screen on top: `leave` (Home ends AR, as always). The watched activity gone or changed (the film ended,
     * Back left the player): its task goes back into its screen, AR comes to the front, then `back`.
     */
    fun followWatch(back: () -> Unit, leave: () -> Unit) {
        main.postDelayed({ background { checkWatch(back, leave) } }, WATCH_POLL_MS)
    }

    private fun checkWatch(back: () -> Unit, leave: () -> Unit) {
        val w = watch ?: return
        if (closed.get() || !helper.running) {
            watch = null
            main.post(leave)
            return
        }
        val tasks = TaskInfo.decodeAll(helper.call(Command.Tasks) ?: "")
        val t = tasks.firstOrNull { it.id == w.task }
        val top = tasks.firstOrNull { it.display == 0 && it.visible }
        when {
            top != null && top.pkg == homePackage -> {
                watch = null
                AppLog.i("${w.source.name}: the home screen took the glasses")
                main.post(leave)
            }
            t != null && t.display == 0 && t.top == w.top -> followWatch(back, leave)
            else -> {
                watch = null
                if (t != null && t.display == 0 && w.source.displayId >= 0) helper.call(Command.MoveTask(t.id, w.source.displayId))
                helper.call(Command.Front)
                focusedDisplay = -1
                focusNow()
                AppLog.i("${w.source.name}: back in its screen")
                main.post(back)
            }
        }
    }

    /** AR came to the front while an app was full screen: the app goes back into its screen (worker). */
    fun endWatch() {
        if (watch == null) return
        background {
            val w = watch ?: return@background
            watch = null
            val t = TaskInfo.decodeAll(helper.call(Command.Tasks) ?: "").firstOrNull { it.id == w.task }
            if (t != null && t.display == 0 && w.source.displayId >= 0) helper.call(Command.MoveTask(t.id, w.source.displayId))
            focusedDisplay = -1
            focusNow()
            AppLog.i("${w.source.name}: back in its screen")
        }
    }

    private val homePackage: String? by lazy {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }

    // MARK: - Displays (GL thread)

    override fun onDisplay(source: VirtualDisplaySource, displayId: Int) {
        val pkg = source.name.removePrefix("app:")
        background {
            if (closed.get()) return@background
            if (!ensureHelper()) return@background
            // The screen went while the helper started: nothing to open.
            if (source.displayId < 0) return@background
            val adopted = adopting.remove(source.name)
            if (adopted != null) {
                if (helper.call(Command.MoveTask(adopted, displayId)) == null) {
                    status("${StationAr.appName(context, pkg)} couldn't move into its screen")
                    return@background
                }
                source.task = adopted
                AppLog.i("${source.name}: task $adopted moved into display $displayId")
                status(null)
                if (wanted == source.name) focusNow()
                return@background
            }
            launch(source, displayId)
        }
    }

    /** Worker: starts the source's app on its display; false when it didn't start. */
    private fun launch(source: VirtualDisplaySource, displayId: Int): Boolean {
        val pkg = source.name.removePrefix("app:")
        val component = launchComponent(pkg)
        if (component == null) {
            status("${StationAr.appName(context, pkg)} has no TV screen to open")
            return false
        }
        val reply = helper.call(Command.Launch(displayId, component))
        if (reply == null) {
            status("${StationAr.appName(context, pkg)} didn't open in its screen")
            return false
        }
        source.task = reply.trim().toIntOrNull() ?: -1
        AppLog.i("${source.name}: started on display $displayId, task ${source.task}")
        status(null)
        if (wanted == source.name) focusNow()
        return true
    }

    // MARK: - An app that closes in its screen

    /** Whether screen `name`'s app closed itself there (OK on the screen opens it again). */
    fun appClosed(name: String): Boolean = sources[name]?.appClosed == true

    /**
     * Worker: an app can close itself inside its screen (Back on its first page, a crash). Its display is empty then,
     * and would keep the remote with nothing to drive. The screen's card says so instead, and the remote goes back to
     * AR, where OK on the screen opens the app again ([reopen]).
     */
    private fun checkClosed() {
        if (closed.get() || !helper.running) return
        val watched = watch?.source
        val open = sources.values.filter { it.displayId >= 0 && it.task >= 0 && it !== watched }
        if (open.isEmpty()) return
        // No answer isn't "no tasks".
        val tasks = TaskInfo.decodeAll(helper.call(Command.Tasks) ?: return)
        var any = false
        for (src in open) {
            if (tasks.any { it.display == src.displayId }) continue
            AppLog.i("${src.name}: its app closed in its screen")
            src.task = -1
            src.appClosed = true
            src.note = CLOSED_NOTE
            any = true
        }
        if (any) focusNow()
    }

    private val lookForClosed = Runnable { background { checkClosed() } }

    /** Opens screen `name`'s app again, after it closed there. */
    fun reopen(name: String) {
        val src = sources[name] ?: return
        if (!src.appClosed) return
        src.note = OPENING_NOTE
        background {
            val id = src.displayId
            if (closed.get() || id < 0 || !ensureHelper()) return@background
            if (launch(src, id)) opened(src, src.task) else src.note = CLOSED_NOTE
        }
    }

    /** Worker: the app is on its screen again, as task `task`. */
    private fun opened(src: VirtualDisplaySource, task: Int) {
        src.task = task
        src.appClosed = false
        src.note = null
        if (wanted == src.name) focusNow()
    }

    override fun onDisplayGone(source: VirtualDisplaySource, displayId: Int) {
        // The GL thread mustn't wait for the helper (it may be busy for a while, starting): the worker closes the task,
        // then the display. Opening the same app again makes a new source, after this one is done.
        sources.remove(source.name, source)
        if (source.task < 0) {
            // No app on it yet (the helper may still be waiting for the Station's approval, for up to a minute): there
            // is no task to close first, and a launch still to come fails on a display that's gone.
            source.release()
            return
        }
        val queued = background {
            val t = source.task
            if (t >= 0 && helper.running) helper.call(Command.RemoveTask(t))
            source.release()
            if (focusedDisplay == displayId) focusedDisplay = -1
            focusNow()
        }
        if (!queued) source.release()
    }

    // MARK: - Focus follows the gaze

    /** The gaze settled on screen `name` (null or not an app: AR keeps the remote). */
    fun gazeOn(name: String?) {
        wanted = if (name != null && sources.containsKey(name)) name else null
        background { focusNow() }
    }

    /** AR's panel opened (`true`): the remote stays with AR until it closes. */
    fun holdForPanel(hold: Boolean) {
        holding = hold
        background { focusNow() }
    }

    /** Worker: moves the focus where it should be now. */
    private fun focusNow() {
        if (closed.get() || !helper.running) return
        val src = if (holding) null else wanted?.let { sources[it] }
        val display = if (src != null && src.task >= 0 && src.displayId >= 0) src.displayId else 0
        if (display == focusedDisplay) return
        // A corner of the display: the touch is cancelled at once, and corners hold the least. Right after an app starts
        // its window may not be there to take the touch yet: a few more tries.
        val focus = if (display == 0 || src == null) Command.Focus(0, 1, 1)
            else Command.Focus(display, src.size.width - 2, src.size.height - 2)
        var ok = false
        for (attempt in 1..FOCUS_TRIES) {
            ok = helper.call(focus) != null
            if (ok || closed.get() || !helper.running) break
            Thread.sleep(FOCUS_RETRY_MS)
        }
        if (!ok) {
            // No window took the touch: its app may have closed there.
            if (display != 0) checkClosed()
            return
        }
        focusedDisplay = display
        AppLog.i("keys: the remote drives display $display")
        main.post { filter.live = display != 0 }
    }

    // MARK: - The helper (worker)

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
        return true
    }

    // MARK: - Keys (main thread, from AR's window)

    /**
     * A key that reached AR's own window while an app has the remote (the Station sends its buttons' keys to the
     * glasses' display, whichever display has the focus): true when it was taken here, for the app or for AR.
     */
    fun onKey(e: KeyEvent): Boolean {
        if (!filter.live) return false
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return false
        if (!helper.running) {
            // The helper is gone: nothing can be passed on to the app, so AR takes the remote back.
            AppLog.w("keys: the helper is gone; the remote stays with AR")
            filter.live = false
            return false
        }
        val injected = e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
        return apply(filter.onKey(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.eventTime, injected))
    }

    private val timeout = Runnable { apply(filter.onTimeout(SystemClock.uptimeMillis())) }

    private fun apply(r: KeyFilter.Result): Boolean {
        r.action?.let { listener.onKeyAction(it) }
        if (r.resend != 0) {
            val code = r.resend
            background { helper.call(Command.Key(code)) }
        }
        r.forward?.let { f ->
            val phase = if (f.down) Command.Key.Phase.DOWN else Command.Key.Phase.UP
            background { helper.call(Command.Key(f.code, phase)) }
        }
        // Back on an app's first page closes it: look a moment later.
        if (r.resend == KeyFilter.BACK || r.forward?.let { it.code == KeyFilter.BACK && !it.down } == true) {
            main.removeCallbacks(lookForClosed)
            main.postDelayed(lookForClosed, CLOSED_CHECK_MS)
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

    private fun status(text: String?) = main.post { if (!closed.get()) listener.onAppStatus(text) }

    /** Runs `r` on the worker; false once it's shut down (AR is closing). */
    private fun background(r: () -> Unit): Boolean = try {
        work.execute(r)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    // MARK: - The end

    /**
     * Leaving AR: closes every app's task, releases the displays and stops the helper. Safe to call twice, and from any
     * thread; the work runs on the worker.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.post {
            filter.live = false
            main.removeCallbacks(timeout)
            main.removeCallbacks(lookForClosed)
        }
        background {
            for (s in sources.values) {
                val t = s.task
                if (t >= 0 && helper.running) helper.call(Command.RemoveTask(t))
                s.release()
            }
            if (helper.running) helper.call(Command.Focus(0, 1, 1))
            helper.stop()
        }
        work.shutdown()
    }

    companion object {
        private const val FOCUS_TRIES = 6
        private const val FOCUS_RETRY_MS = 300L
        /** How often to look at the glasses' display while an app is full screen (each look runs `am stack list`). */
        private const val WATCH_POLL_MS = 2000L
        /** After Back went to an app: time for it to finish before looking whether it closed. */
        private const val CLOSED_CHECK_MS = 1000L
        private const val CLOSED_NOTE = "Closed  ·  OK opens it again"
        private const val OPENING_NOTE = "Opening…"

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
