package io.github.tuthan.stationar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import io.github.tuthan.stationar.apps.AppScreens
import io.github.tuthan.stationar.core.compat.AppCompat
import io.github.tuthan.stationar.core.helper.TaskInfo
import io.github.tuthan.stationar.core.keys.KeyFilter
import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.pose.AxisCalibration
import io.github.tuthan.stationar.core.scene.Layout
import io.github.tuthan.stationar.core.scene.PanelLayout
import io.github.tuthan.stationar.core.scene.Presets
import io.github.tuthan.stationar.core.scene.ScreenSpec
import io.github.tuthan.stationar.core.store.Settings
import io.github.tuthan.stationar.core.stream.Handshake
import io.github.tuthan.stationar.glasses.GlassesSession
import io.github.tuthan.stationar.receiver.Advertiser
import io.github.tuthan.stationar.receiver.StreamServer
import io.github.tuthan.stationar.receiver.Tls
import io.github.tuthan.stationar.render.PanelView
import io.github.tuthan.stationar.render.Renderer
import io.github.tuthan.stationar.sensors.SensorSource
import io.github.tuthan.stationar.sources.Source
import io.github.tuthan.stationar.sources.StreamSource
import io.github.tuthan.stationar.sources.TestCardSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AR mode: screens that stay put in the room as you turn your head. The GL scene is [Renderer]'s; this activity owns
 * the layout, the keys, the panel, the HUD, the glasses session and the laptop receiver. Leaving it (Back, Home, or
 * anything that stops it) ends AR: everything is released, the receiver's port closes, and the glasses go back to
 * their refresh rate, in 2D.
 */
class ArActivity : Activity(), Renderer.Listener, StreamServer.Listener, AppScreens.Listener {
    private lateinit var settings: Settings
    private lateinit var sensors: SensorSource
    private lateinit var renderer: Renderer
    private lateinit var glView: GLSurfaceView
    private lateinit var glasses: GlassesSession
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var layout: Layout = Presets.welcome()
    private var tracking = false
    private var focusedIndex = -1
    private var leaving = false
    private var resumed = false

    private val panel = PanelLayout(PanelView.PER_ROW)
    private var panelOpen = false
    private var panelHeader = ""
    private var calibration: Quat? = null
    private var calibrating = false
    /** Calibration was offered this session (it asks once; the panel's *Calibrate* is there after). */
    private var calibrationOffered = false

    /** A passing HUD line (what the buttons do, how to leave), and whether the buttons' one was shown this session. */
    private var hintLine: String? = null
    private var remoteHinted = false
    /** When Back last asked to leave AR (a second Back soon after leaves). */
    private var backAt = 0L

    // The laptop receiver. Its sources outlive connections (and layouts) while AR is open.
    private val streams = ConcurrentHashMap<String, StreamSource>()
    private var server: StreamServer? = null
    private lateinit var advertiser: Advertiser
    @Volatile private var fingerprint = ""
    private var pairingLine: String? = null
    /** Streams whose screen you put away (a preset without them): they're refused until a layout shows them again. */
    private val dismissed = HashSet<String>()
    /** The laptop was asked for another screen ([Handshake.MORE]) and it hasn't come yet. */
    private var moreAsked = false

    // Android apps in screens, and the remote following the gaze.
    private lateinit var apps: AppScreens
    private var appLine: String? = null
    /** The screen the screen menu (held Back) is for. */
    private var menuScreen = -1
    /** Another app that opened over AR, while the panel asks what to do with it. */
    private var offered: TaskInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = StationAr.settings(this)
        sensors = SensorSource(getSystemService(SensorManager::class.java))
        glasses = GlassesSession(this)
        advertiser = Advertiser(this)
        apps = AppScreens(this, this)
        renderer = Renderer(sensors, settings.axes ?: AxisCalibration.DEFAULT, this)
        renderer.pose.predictSeconds = settings.predictMs / 1000f
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        layout = startLayout(intent)
        val scale = settings.size
        glView.queueEvent {
            renderer.setSizeScale(scale)
            renderer.setLayout(layout) { name -> sourceFor(name) }
        }
        AppLog.i("AR: open, layout ${layout.encode()}")
        intent?.getStringExtra(EXTRA_PACKAGE)?.let { pkg -> main.post { openApp(pkg) } }
        work.execute { startReceiver() }
        registerReceiver(homeKey, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
    }

    /**
     * Home ends AR, wherever it went. With an app's display focused, Android looks for a home screen on that display,
     * finds none and only says so (CLOSE_SYSTEM_DIALOGS, reason "homekey"); the key itself never reaches AR.
     */
    private val homeKey = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(SYSTEM_DIALOG_REASON) != HOME_KEY) return
            AppLog.i("AR: Home pressed")
            leave()
        }
    }

    /**
     * The layout to open in: the intent's preset; else, asked to wait for the laptop, its first monitor straight
     * ahead; else the setting, the last one, or the welcome card.
     */
    private fun startLayout(intent: Intent?): Layout {
        val asked = intent?.getStringExtra(EXTRA_PRESET)
        if (asked == null && intent?.getBooleanExtra(EXTRA_LAPTOP, false) == true) {
            return Layout(mutableListOf(ScreenSpec("laptop:0", 0f, 0f, Presets.ONE_TO_ONE)))
        }
        val preset = if (asked != null && (asked in Presets.NAMES || asked == Presets.LAST)) asked else settings.startPreset
        val l = (if (preset == Presets.LAST) settings.lastLayout else Presets.layout(preset)) ?: Presets.welcome()
        // An app that can't be in a screen (full-screen only, or not installed any more) isn't opened with the layout:
        // one that hands you to another app would end AR each time it opened.
        l.screens.removeAll { it.kind == "app" && !fitsScreen(it.source.removePrefix("app:")) }
        return if (l.screens.isEmpty()) Presets.welcome() else l
    }

    private fun fitsScreen(pkg: String) = AppCompat.inScreen(pkg) && launchIntent(pkg) != null

    /** The intent a TV launcher would open `pkg` with. */
    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    /** Station Home's AR Mode while AR is open already: a preset or an app it asks for. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PRESET)?.let { preset ->
            if (preset in Presets.NAMES) act("preset:$preset")
        }
        intent.getStringExtra(EXTRA_PACKAGE)?.let { openApp(it) }
    }

    /** Makes the source a screen asks for (GL thread, from Renderer.setLayout). */
    private fun sourceFor(name: String): Source? = when {
        name == ScreenSpec.CARD -> TestCardSource(sensors.available && GlassesSession.plugged(this))
        name in STREAMS -> stream(name)
        name.startsWith("app:") -> apps.source(name)
        else -> null
    }

    private fun stream(name: String): StreamSource = streams.getOrPut(name) {
        StreamSource(name).also { it.label = if (name == "wide") "Laptop (wide)" else StationAr.screenLabel(name) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(comeBackOrLeave)
        apps.endWatch()
        sensors.start()
        glView.onResume()
        val want60 = settings.refresh60
        work.execute { glasses.enter(want60) }
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(tick)
        sensors.stop()
        glView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || leaving) return
        // AR doesn't run in the background: whatever took display 0 (Home, an app) ends it. Except, while app screens
        // are up, an app in one of them opening something of its own on display 0 (VLC's player): that goes into the
        // app's screen, and AR comes back; or another app opening there (an AirPlay player): AR comes back and asks.
        if (apps.count == 0) {
            leave()
            return
        }
        // An app you chose to watch full screen: AR waits behind it; the watch's own poll brings it back or ends it.
        if (apps.watching) return
        apps.reclaim { moved, other ->
            if (!moved && other == null) {
                leave()
                return@reclaim
            }
            if (other != null) offerScreen(other)
            if (!resumed) main.postDelayed(comeBackOrLeave, RECLAIM_WAIT_MS)
        }
    }

    /** Another app opened over AR (it's behind AR again now): show it in a screen, or leave AR for it. */
    private fun offerScreen(task: TaskInfo) {
        offered = task
        panelHeader = "${StationAr.appName(this, task.pkg)} opened on the glasses"
        panel.set(listOf("" to listOf(
            PanelLayout.Item("offer-screen", "Show in a screen"),
            PanelLayout.Item("offer-full", "Full screen", "leaves AR"),
        )))
        showPanelNow()
    }

    /**
     * An app went full screen over AR. Android 11 can leave AR resumed behind a task moved onto its display (only
     * "top resumed" moves), so AR stops drawing and tracking itself until [wake] or its next onResume.
     */
    private fun rest() {
        main.removeCallbacks(tick)
        sensors.stop()
        glView.onPause()
    }

    /** The watched app went back into its screen while AR stayed resumed: AR draws and tracks again. */
    private fun wake() {
        main.removeCallbacks(tick)
        sensors.start()
        glView.onResume()
        tick.run()
    }

    /** After a reclaim: AR should be on top again by now; if it isn't (Home was pressed meanwhile), AR ends. */
    private val comeBackOrLeave = Runnable { if (!resumed) leave() }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(homeKey)
        leave()
        // If the GL thread ended before it released the scene, the apps' tasks and displays still go.
        apps.close()
        work.shutdown()
    }

    /** Ends AR once: saves the layout, releases the scene, puts the glasses back. */
    private fun leave() {
        if (leaving) return
        leaving = true
        AppLog.i("AR: leaving")
        glView.queueEvent {
            val last = renderer.currentLayout()
            // A screen whose app closed or handed you to another app isn't opened again next time.
            last.screens.removeAll { it.kind == "app" && apps.appClosed(it.source) }
            if (last.screens.isEmpty()) last.screens.addAll(Presets.welcome().screens)
            // After onDestroy the worker is gone: saved here then.
            if (!background { settings.lastLayout = last }) settings.lastLayout = last
            renderer.release()
            apps.close()
        }
        background {
            advertiser.stop()
            server?.stop()
            server = null
        }
        background { glasses.leave() }
        if (!isFinishing) finish()
    }

    /** Runs `r` on the worker; false once it's shut down (AR is closing). */
    private fun background(r: () -> Unit): Boolean = try {
        work.execute(r)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    // MARK: - Renderer.Listener (GL thread)

    override fun onFocus(index: Int, source: String?) {
        main.post {
            focusedIndex = index
            updateHud()
            apps.gazeOn(source)
        }
    }

    // MARK: - AppScreens.Listener (main thread)

    override fun onAppStatus(text: String?) {
        appLine = text
        updateHud()
    }

    override fun onKeyAction(action: KeyFilter.Action) {
        AppLog.i("keys: ${action.name.lowercase()}, kept from the app")
        when (action) {
            KeyFilter.Action.AR_MENU -> togglePanel()
            KeyFilter.Action.RECENTRE -> recentre()
            KeyFilter.Action.SCREEN_MENU -> openScreenMenu()
            KeyFilter.Action.LEAVE -> leave()
        }
    }

    override fun onTracking(tracking: Boolean) {
        main.post {
            this.tracking = tracking
            AppLog.i("AR: head tracking " + if (tracking) "on" else "off (no samples: the D-pad turns the view)")
            if (tracking && settings.axes == null && !calibrating && !calibrationOffered) {
                calibrationOffered = true
                startCalibration()
            }
            updateHud()
        }
    }

    override fun onRemote(toApp: Boolean) {
        if (!toApp || remoteHinted) return
        remoteHinted = true
        hint("Hold Back: screen menu  ·  Hold OK: re-centre", REMOTE_HINT_MS)
    }

    // MARK: - The laptop receiver

    /** Opens the port and advertises it (worker thread). */
    private fun startReceiver() {
        if (leaving) return
        try {
            val fp = Tls.fingerprint()
            fingerprint = fp
            val s = StreamServer(settings.port, StationAr.pairing(this), this)
            s.start()
            server = s
            advertiser.start(settings.port, fp)
        } catch (e: Exception) {
            AppLog.w("receiver: couldn't start", e)
        }
    }

    override fun onPairing(name: String, pin: String?) {
        main.post {
            pairingLine = if (pin == null) null else "Pair \"$name\": PIN $pin  ·  key ${Tls.shortKey(fingerprint)}"
            updateHud()
        }
    }

    override fun onStream(hello: Handshake.Request.Hello, laptop: String, connection: StreamServer.Connection) {
        if (StreamSource.decoderFor(hello) == null) {
            connection.answer("NO_DECODER")
            return
        }
        // A screen must show the stream: add one if none does (unless you put it away), then play.
        val refusal = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        main.post {
            if (leaving || hello.source in dismissed) {
                refusal.set("CLOSED")
                done.countDown()
                return@post
            }
            glView.queueEvent {
                val now = renderer.currentLayout()
                // The screen that shows it takes its shape: a mirrored 21:9 monitor isn't squashed.
                val next = if (shows(now, hello.source)) {
                    Presets.fit(now, hello.source, hello.width, hello.height)
                } else {
                    Presets.place(now, hello.source, hello.width, hello.height) ?: run {
                        refusal.set("BUSY")
                        null
                    }
                }
                if (next != null) {
                    val added = !shows(now, hello.source)
                    renderer.setLayout(next) { name -> sourceFor(name) }
                    main.post {
                        layout = next
                        if (added && moreAsked) {
                            moreAsked = false
                            main.removeCallbacks(clearHint)
                            clearHint.run()
                        }
                        updateHud()
                    }
                }
                done.countDown()
            }
        }
        if (!done.await(3, TimeUnit.SECONDS)) refusal.set("CLOSED")
        refusal.get()?.let {
            AppLog.i("receiver: ${hello.source} refused: $it")
            connection.answer(it)
            return
        }
        stream(hello.source).play(hello, laptop, connection)
    }

    /** The laptop sources streaming now. */
    private fun streaming(): Set<String> = streams.filterValues { it.connected }.keys

    private fun shows(l: Layout, source: String) =
        if (source == "wide") l.screens.any { it.kind == "wide" } else l.indexOf(source) >= 0

    /** A laptop screen added beside the first (Laptop 2 and up): put away, it's let go on the laptop, its monitor removed. */
    private fun added(stream: String) = stream.startsWith("laptop:") && stream != "laptop:0"

    /**
     * "+ Laptop screen": Laptop 1, if you put it away, comes back (its laptop asks again every 2 s); else the laptop is
     * asked for one more monitor ([Handshake.MORE]), which [Presets.place] puts beside the others when it connects.
     */
    private fun addLaptopScreen() {
        closePanel()
        if ("laptop:0" in dismissed) {
            dismissed.remove("laptop:0")
            hint("${StationAr.screenLabel("laptop:0")} comes back in a moment", LAPTOP_HINT_MS)
            return
        }
        val live = streaming()
        val laptops = live.filter { it.startsWith("laptop:") }
        when {
            live.isEmpty() -> hint("Run station-ar-send on the laptop first", LAPTOP_HINT_MS)
            laptops.isEmpty() -> hint("A --wide stream can't add screens", LAPTOP_HINT_MS)
            laptops.size >= Handshake.MAX_LAPTOP_SCREENS ->
                hint("${Handshake.MAX_LAPTOP_SCREENS} laptop screens at most", LAPTOP_HINT_MS)
            else -> {
                // Screens closed before were let go on the laptop: the one it makes now may take one of their numbers.
                dismissed.removeAll { added(it) }
                val asked = streams.getValue(laptops.min())
                moreAsked = true
                hint("Asking the laptop for another screen…", MORE_WAIT_MS)
                work.execute {
                    if (!asked.tell(Handshake.MORE)) main.post { moreAsked = false; hint("The laptop didn't take the request: try again", LAPTOP_HINT_MS) }
                }
                main.postDelayed({
                    if (moreAsked) {
                        moreAsked = false
                        hint("No new screen came: update station-ar-send on the laptop", LAPTOP_HINT_MS)
                    }
                }, MORE_WAIT_MS)
            }
        }
    }

    // MARK: - Keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The Station delivers its own buttons' keys to this display even while an app's screen has the focus: while
        // an app has the remote, they go on to it from here.
        if (apps.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) togglePanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                dpad(keyCode)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            recentre()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!panelOpen) openScreenMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isTracking && !event.isCanceled) ok()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.isTracking && !event.isCanceled) {
                    if (panelOpen) closePanel() else backToLeave()
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Back with nothing open leaves AR. With apps in screens or a laptop streaming, a stray Back (a glance away from an
     * app, then Back meant for it) would close them all: it takes a second Back then.
     */
    private fun backToLeave() {
        val busy = apps.count > 0 || streams.values.any { it.connected }
        val now = SystemClock.uptimeMillis()
        if (!busy || now - backAt < LEAVE_CONFIRM_MS) {
            leave()
            return
        }
        backAt = now
        hint("Press Back again to leave AR", LEAVE_CONFIRM_MS)
    }

    private fun dpad(keyCode: Int) {
        val dx = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        val dy = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        if (panelOpen) {
            if (panel.move(dx, dy)) drawPanel()
            return
        }
        if (!tracking) {
            // No glasses: the D-pad is your head.
            glView.queueEvent { renderer.turn(dx * TURN_STEP, -dy * TURN_STEP) }
            return
        }
        // With glasses: left and right nudge the focused screen.
        if (dx != 0) {
            val i = focusedIndex
            if (i >= 0) glView.queueEvent { renderer.nudge(i, dx * NUDGE_STEP, 0f) }
        }
    }

    private fun ok() {
        if (calibrating) {
            if (panel.focusedItem()?.id == "calibrate-later") closePanel() else calibrationStep()
            return
        }
        if (panelOpen) {
            panel.focusedItem()?.let { act(it.id) }
            return
        }
        // On a screen whose app closed, OK opens it again.
        val spec = layout.screens.getOrNull(focusedIndex)
        if (spec != null && spec.kind == "app" && apps.appClosed(spec.source)) {
            apps.reopen(spec.source)
            return
        }
        openPanel()
    }

    private fun recentre() {
        glView.queueEvent { renderer.recentre() }
        AppLog.i("AR: re-centred")
    }

    // MARK: - The panel

    private fun togglePanel() {
        if (panelOpen) closePanel() else openPanel()
    }

    private fun openPanel() {
        panelHeader = "Station AR"
        panel.set(panelSections())
        showPanelNow()
    }

    /** The panel is up: it has the remote (AR's display keeps the focus) until it closes. */
    private fun showPanelNow() {
        if (!panelOpen) AppLog.i("AR: panel open")
        panelOpen = true
        apps.holdForPanel(true)
        drawPanel()
    }

    private fun closePanel() {
        if (panelOpen) AppLog.i("AR: panel closed")
        panelOpen = false
        calibrating = false
        menuScreen = -1
        apps.holdForPanel(false)
        glView.queueEvent { renderer.hidePanel() }
    }

    /** Held Back: what to do with the screen you look at (move it, size it, close it). */
    private fun openScreenMenu() {
        val i = focusedIndex
        val spec = layout.screens.getOrNull(i) ?: return
        menuScreen = i
        panelHeader = StationAr.screenLabel(spec.source)
        // Done first: OK straight away does nothing to the screen.
        val items = arrayListOf(
            PanelLayout.Item("screen-done", "Done"),
            // The Station's buttons have no Menu key: while an app has the remote, this is the way to AR's own panel.
            PanelLayout.Item("ar-menu", "AR menu", "apps, layout"),
            PanelLayout.Item("screen-close", "Close", if (added(spec.source)) "and its monitor" else ""),
        )
        // A film in a screen costs the GPU a pass per frame; full screen it costs nothing, without head tracking.
        if (spec.kind == "app") {
            items.add(0, if (apps.appClosed(spec.source)) PanelLayout.Item("screen-reopen", "Open again")
                else PanelLayout.Item("screen-full", "Watch full screen", "Back returns"))
        }
        val place = listOf(
            PanelLayout.Item("screen-left", "Move left"),
            PanelLayout.Item("screen-right", "Move right"),
            PanelLayout.Item("screen-up", "Move up"),
            PanelLayout.Item("screen-down", "Move down"),
            PanelLayout.Item("screen-bigger", "Bigger"),
            PanelLayout.Item("screen-smaller", "Smaller"),
        )
        panel.set(listOf("" to items, "Place" to place))
        showPanelNow()
    }

    private fun drawPanel() {
        val header = panelHeader
        // PanelLayout's rows are immutable lists: the GL thread can draw them while the panel moves on.
        val rows = panel.rows
        val row = panel.row
        val col = panel.col
        val icons = HashMap<String, Bitmap>()
        for (r in rows) for (item in r.items) {
            if (item.id.startsWith("app:")) StationAr.appIcon(this, item.id.removePrefix("app:"))?.let { icons[item.id] = it }
        }
        glView.queueEvent { renderer.showPanel({ it.draw(rows, row, col, header, icons) }) }
    }

    private fun panelSections(): List<Pair<String, List<PanelLayout.Item>>> {
        val layoutItems = ArrayList<PanelLayout.Item>()
        layoutItems.add(PanelLayout.Item("recentre", "Re-centre"))
        val laptop = streaming().isNotEmpty()
        for (name in Presets.NAMES) layoutItems.add(PanelLayout.Item("preset:$name", Presets.label(name), Presets.detail(name, laptop)))
        val laptopScreens = streaming().count { it.startsWith("laptop:") }
        layoutItems.add(PanelLayout.Item("more-laptop", "+ Laptop screen", when {
            !laptop -> "needs the laptop"
            laptopScreens >= Handshake.MAX_LAPTOP_SCREENS -> "${Handshake.MAX_LAPTOP_SCREENS} at most"
            else -> "beside the others"
        }))
        val view = listOf(
            PanelLayout.Item("size-", "Smaller", percent(settings.size)),
            PanelLayout.Item("size+", "Bigger"),
            PanelLayout.Item("refresh", if (settings.refresh60) "60 Hz" else "120 Hz", "in AR"),
            PanelLayout.Item("predict", "Prediction", "${settings.predictMs} ms"),
            PanelLayout.Item("calibrate", "Calibrate", if (settings.axes == null) "not yet" else ""),
            PanelLayout.Item("start", "Start in", Presets.label(settings.startPreset).let { if (settings.startPreset == Presets.LAST) "last layout" else it }),
        )
        val laptops = StationAr.pairing(this).laptops().map { name ->
            val on = streams.values.any { it.connected && it.label == name }
            PanelLayout.Item("forget:$name", name, if (on) "connected · OK forgets" else "paired · OK forgets")
        }
        val sections = arrayListOf("Layout" to layoutItems, "Apps" to appItems(), "View" to view)
        if (laptops.isNotEmpty()) sections.add("Laptops" to laptops)
        sections.add("" to listOf(PanelLayout.Item("leave", "Leave AR")))
        return sections
    }

    /** The installed apps, the ones that work in a screen first, each with how it does there. */
    private fun appItems(): List<PanelLayout.Item> {
        val list = AppScreens.launchable(this)
            .sortedWith(compareBy({ AppCompat.rank(it.first) }, { it.second.lowercase(Locale.ROOT) }))
            .take(MAX_LISTED_APPS)
        return list.map { (pkg, label) ->
            StationAr.appNames[pkg] = label
            val detail = when (AppCompat.of(pkg).status) {
                AppCompat.Status.WORKS -> "works"
                AppCompat.Status.UNTESTED -> "not tried"
                AppCompat.Status.PARTLY -> "partly"
                AppCompat.Status.NO_VIDEO, AppCompat.Status.DRM -> "opens full screen"
            }
            PanelLayout.Item("app:$pkg", label, detail)
        }
    }

    private fun percent(f: Float) = "${(f * 100).toInt()} %"

    private fun act(id: String) {
        when {
            id == "recentre" -> {
                recentre()
                closePanel()
            }
            id.startsWith("preset:") -> {
                // Cinema shows what you look at, big, if it's there (an app, a streaming laptop); Desk + app keeps your
                // app screen.
                val name = id.removePrefix("preset:")
                val focused = layout.screens.getOrNull(focusedIndex)?.source
                val first = Presets.cinemaSource(focused, layout.screens.map { it.source }, streaming())
                val app = layout.screens.firstOrNull { it.kind == "app" }?.source ?: ScreenSpec.CARD
                val l = Presets.layout(name, first, app) ?: return
                if (name != Presets.CINEMA && streaming().isEmpty()) {
                    hint("Desk shows the laptop's screens: run station-ar-send on it", LAPTOP_HINT_MS)
                }
                // A laptop streaming to a screen this preset doesn't have stays away until one does; one you added is
                // let go on the laptop, told before its screen goes (taking the screen away ends the connection).
                val letGo = ArrayList<StreamSource>()
                for (name in STREAMS) {
                    val s = streams[name]
                    if (shows(l, name)) dismissed.remove(name) else if (s?.connected == true) {
                        dismissed.add(name)
                        if (added(name)) letGo.add(s)
                    }
                }
                closePanel()
                if (letGo.isEmpty()) setLayout(l) else work.execute {
                    for (s in letGo) s.gone()
                    main.post { setLayout(l) }
                }
            }
            id == "more-laptop" -> addLaptopScreen()
            id == "size-" || id == "size+" -> {
                settings.size = settings.size + if (id == "size+") 0.05f else -0.05f
                val s = settings.size
                glView.queueEvent { renderer.setSizeScale(s) }
                panel.set(panelSections())
                drawPanel()
            }
            id == "refresh" -> {
                settings.refresh60 = !settings.refresh60
                val want60 = settings.refresh60
                work.execute { glasses.enter(want60) }
                panel.set(panelSections())
                drawPanel()
            }
            id == "predict" -> {
                settings.predictMs = if (settings.predictMs >= Settings.MAX_PREDICT_MS) 0 else settings.predictMs + 10
                val ms = settings.predictMs
                glView.queueEvent { renderer.pose.predictSeconds = ms / 1000f }
                panel.set(panelSections())
                drawPanel()
            }
            id == "calibrate" -> startCalibration()
            id == "start" -> {
                val order = listOf(Presets.LAST) + Presets.NAMES
                settings.startPreset = order[(order.indexOf(settings.startPreset) + 1) % order.size]
                panel.set(panelSections())
                drawPanel()
            }
            id.startsWith("forget:") -> {
                val name = id.removePrefix("forget:")
                StationAr.pairing(this).forget(name)
                for (s in streams.values) if (s.label == name) s.disconnect()
                AppLog.i("receiver: forgot \"$name\"")
                panelHeader = "Forgot $name: it pairs again next time"
                panel.set(panelSections())
                drawPanel()
            }
            id == "leave" -> leave()
            id.startsWith("app:") -> openApp(id.removePrefix("app:"))
            id == "screen-left" || id == "screen-right" -> {
                val i = menuScreen
                val d = if (id == "screen-left") -SCREEN_STEP else SCREEN_STEP
                if (i >= 0) glView.queueEvent { renderer.nudge(i, d, 0f) }
            }
            id == "screen-up" || id == "screen-down" -> {
                val i = menuScreen
                val d = if (id == "screen-up") SCREEN_PITCH_STEP else -SCREEN_PITCH_STEP
                if (i >= 0) glView.queueEvent { renderer.nudge(i, 0f, d) }
            }
            id == "screen-bigger" || id == "screen-smaller" -> {
                val i = menuScreen
                val f = if (id == "screen-bigger") SCREEN_GROW else 1f / SCREEN_GROW
                if (i >= 0) glView.queueEvent { renderer.resize(i, f) }
            }
            id == "screen-close" -> {
                closeScreen(menuScreen)
                closePanel()
            }
            id == "screen-done" -> closePanel()
            id == "ar-menu" -> {
                menuScreen = -1
                openPanel()
            }
            id == "screen-reopen" -> {
                val source = layout.screens.getOrNull(menuScreen)?.source
                closePanel()
                if (source != null) apps.reopen(source)
            }
            id == "screen-full" -> {
                val source = layout.screens.getOrNull(menuScreen)?.source ?: return closePanel()
                closePanel()
                apps.watchFullScreen(source) { ok ->
                    if (!ok) {
                        onAppStatus("${StationAr.screenLabel(source)} couldn't go full screen")
                        return@watchFullScreen
                    }
                    rest()
                    apps.followWatch(back = { if (resumed) wake() else main.postDelayed(comeBackOrLeave, RECLAIM_WAIT_MS) }, leave = { leave() })
                }
            }
            id == "offer-screen" -> {
                val t = offered ?: return closePanel()
                offered = null
                apps.adopt(t.pkg, t.id)
                openApp(t.pkg)
            }
            id == "offer-full" -> leave()
        }
    }

    /** Opens an app in a screen where you look; one that only plays full screen opens so, and AR leaves for it. */
    private fun openApp(pkg: String) {
        val compat = AppCompat.of(pkg)
        val label = StationAr.appName(this, pkg)
        if (!AppCompat.inScreen(pkg)) {
            val intent = launchIntent(pkg)
            if (intent == null) {
                panelHeader = "$label: ${compat.note}"
                drawPanel()
                return
            }
            AppLog.i("AR: $pkg plays full screen only; opening it so")
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            leave()
            return
        }
        if (layout.screens.count { it.kind == "app" } >= MAX_APP_SCREENS && layout.indexOf("app:$pkg") < 0) {
            panelHeader = "Two app screens at most: hold Back on one to close it"
            drawPanel()
            return
        }
        closePanel()
        glView.queueEvent {
            val placed = Presets.placeApp(renderer.currentLayout(), "app:$pkg", renderer.gazeYaw)
            if (placed == null) {
                main.post { onAppStatus("No room for another screen") }
                return@queueEvent
            }
            renderer.setLayout(placed) { name -> sourceFor(name) }
            main.post { layout = placed }
            renderer.focusNow(placed.indexOf("app:$pkg"))
            AppLog.i("AR: opened $pkg in a screen")
        }
    }

    /**
     * Takes screen `index` away: an app's closes; a laptop screen you added is let go on the laptop (its monitor
     * removed); Laptop 1 and a wide stream stay away until a layout shows them again.
     */
    private fun closeScreen(index: Int) {
        val source = layout.screens.getOrNull(index)?.source ?: return
        val stream = if (source.startsWith("wide:")) "wide" else source
        val s = streams[stream]
        if (stream in STREAMS && s?.connected == true) {
            dismissed.add(stream)
            if (added(stream)) {
                // Told before its screen goes: taking the screen away ends the connection.
                work.execute {
                    s.gone()
                    main.post { removeScreen(source) }
                }
                return
            }
        }
        removeScreen(source)
    }

    private fun removeScreen(source: String) {
        glView.queueEvent {
            val now = renderer.currentLayout()
            val i = now.indexOf(source)
            if (i < 0) return@queueEvent
            now.screens.removeAt(i)
            if (now.screens.isEmpty()) now.screens.addAll(Presets.welcome().screens)
            renderer.setLayout(now) { name -> sourceFor(name) }
            main.post {
                layout = now
                updateHud()
            }
            AppLog.i("AR: closed the $source screen")
        }
    }

    private fun setLayout(chosen: Layout) {
        // A preset's laptop screens are 16:9: those showing a stream take its shape.
        var l = chosen
        for ((name, s) in streams) Presets.fit(l, name, s.width, s.height)?.let { l = it }
        layout = l
        glView.queueEvent { renderer.setLayout(l) { name -> sourceFor(name) } }
        AppLog.i("AR: layout ${l.encode()}")
    }

    // MARK: - Head-tracking calibration: the glasses' axes, from looking ahead and then up

    private fun startCalibration() {
        if (!tracking) return
        calibrating = true
        calibration = null
        panelHeader = "Calibrate: look straight ahead, then press OK"
        panel.set(calibrationItems("looking ahead"))
        panel.focus("calibrate-ok")
        showPanelNow()
    }

    /** The calibration step's tiles: OK for the step, and Later (the panel's *Calibrate* is there for then). */
    private fun calibrationItems(step: String) =
        listOf("" to listOf(PanelLayout.Item("calibrate-ok", "OK", step), PanelLayout.Item("calibrate-later", "Later")))

    private fun calibrationStep() {
        val s = SensorSource.Sample().also { sensors.read(it) }
        val q = Quat(s.w, s.x, s.y, s.z).normalize()
        val ahead = calibration
        if (ahead == null) {
            calibration = q
            panelHeader = "Now look up (about 30°), then press OK"
            panel.set(calibrationItems("looking up"))
            drawPanel()
            return
        }
        val axes = AxisCalibration.fromRotations(ahead, q)
        if (axes == null) {
            calibration = null
            panelHeader = "That didn't work: look straight ahead, then press OK"
            panel.set(calibrationItems("try again"))
            drawPanel()
            return
        }
        settings.axes = axes
        AppLog.i("AR: head axes calibrated: $axes")
        calibrating = false
        glView.queueEvent {
            renderer.pose.deviceFromHead.set(axes)
            renderer.pose.recentre()
        }
        closePanel()
    }

    // MARK: - HUD

    private val tick = object : Runnable {
        override fun run() {
            updateHud()
            main.postDelayed(this, 1000)
        }
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Shows `text` on the HUD for `ms`, over everything but a pairing PIN. */
    private fun hint(text: String, ms: Long) {
        hintLine = text
        main.removeCallbacks(clearHint)
        main.postDelayed(clearHint, ms)
        updateHud()
    }

    private val clearHint = Runnable {
        hintLine = null
        updateHud()
    }

    private fun updateHud() {
        pairingLine?.let {
            glView.queueEvent { renderer.setHud(it, overPanel = true) }
            return
        }
        hintLine?.let {
            glView.queueEvent { renderer.setHud(it) }
            return
        }
        appLine?.let {
            glView.queueEvent { renderer.setHud(it) }
            return
        }
        val parts = ArrayList<String>()
        parts.add(clock.format(Date()))
        battery()?.let { parts.add(it) }
        if (!tracking) parts.add("no head tracking")
        val laptop = streams.values.firstOrNull { it.connected }?.label
        if (laptop != null) parts.add(laptop)
        val spec = layout.screens.getOrNull(focusedIndex)
        if (spec != null && layout.screens.size > 1) parts.add(StationAr.screenLabel(spec.source))
        val line = parts.joinToString("  ·  ")
        glView.queueEvent { renderer.setHud(line) }
    }

    private fun battery(): String? {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return "${level * 100 / scale} %" + if (plugged) " ⚡" else ""
    }

    companion object {
        const val ACTION_ENTER_AR = "io.github.tuthan.stationar.action.ENTER_AR"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LAPTOP = "laptop"
        private val STREAMS = (0 until Handshake.MAX_LAPTOP_SCREENS).map { "laptop:$it" } + "wide"
        private const val MAX_APP_SCREENS = 2
        private const val MAX_LISTED_APPS = 12
        private const val SCREEN_STEP = 10f
        private const val SCREEN_PITCH_STEP = 5f
        private const val SCREEN_GROW = 1.1f
        /** How long the laptop has to bring a screen asked for: a monitor made, settled, connected, encoding. */
        private const val MORE_WAIT_MS = 10_000L
        private const val TURN_STEP = 5f
        private const val NUDGE_STEP = 5f
        private const val RECLAIM_WAIT_MS = 2000L
        /** How long a second Back leaves AR after the first asked. */
        private const val LEAVE_CONFIRM_MS = 3000L
        private const val REMOTE_HINT_MS = 6000L
        private const val LAPTOP_HINT_MS = 6000L
        // The framework's (hidden) extra on CLOSE_SYSTEM_DIALOGS, and its value for Home.
        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val HOME_KEY = "homekey"
    }
}
