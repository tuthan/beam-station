package io.github.tuthan.stationar

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.pose.AxisCalibration
import io.github.tuthan.stationar.core.scene.Layout
import io.github.tuthan.stationar.core.scene.PanelLayout
import io.github.tuthan.stationar.core.scene.Presets
import io.github.tuthan.stationar.core.scene.ScreenSpec
import io.github.tuthan.stationar.core.store.Settings
import io.github.tuthan.stationar.glasses.GlassesSession
import io.github.tuthan.stationar.render.PanelView
import io.github.tuthan.stationar.render.Renderer
import io.github.tuthan.stationar.sensors.SensorSource
import io.github.tuthan.stationar.sources.Source
import io.github.tuthan.stationar.sources.TestCardSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * AR mode: screens that stay put in the room as you turn your head. The GL scene is [Renderer]'s; this activity owns
 * the layout, the keys, the panel, the HUD and the glasses session. Leaving it (Back, Home, or anything that stops it)
 * ends AR: everything is released, and the glasses go back to their refresh rate, in 2D.
 */
class ArActivity : Activity(), Renderer.Listener {
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

    private val panel = PanelLayout(PanelView.PER_ROW)
    private var panelOpen = false
    private var panelHeader = ""
    private var calibration: Quat? = null
    private var calibrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = StationAr.settings(this)
        sensors = SensorSource(getSystemService(SensorManager::class.java))
        glasses = GlassesSession(this)
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
    }

    /** The layout to open in: the intent's preset, else the setting, else the last one, else the welcome card. */
    private fun startLayout(intent: Intent?): Layout {
        val asked = intent?.getStringExtra(EXTRA_PRESET)
        val preset = if (asked != null && (asked in Presets.NAMES || asked == Presets.LAST)) asked else settings.startPreset
        return (if (preset == Presets.LAST) settings.lastLayout else Presets.layout(preset)) ?: Presets.welcome()
    }

    /** Makes the source a screen asks for (GL thread, from Renderer.setLayout). */
    private fun sourceFor(name: String): Source? = when {
        name == ScreenSpec.CARD -> TestCardSource(sensors.available && GlassesSession.plugged(this))
        else -> null
    }

    override fun onResume() {
        super.onResume()
        sensors.start()
        glView.onResume()
        val want60 = settings.refresh60
        work.execute { glasses.enter(want60) }
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        sensors.stop()
        glView.onPause()
    }

    override fun onStop() {
        super.onStop()
        // AR doesn't run in the background: whatever took display 0 (Home, an app) ends it.
        if (!isChangingConfigurations) leave()
    }

    override fun onDestroy() {
        super.onDestroy()
        leave()
        work.shutdown()
    }

    /** Ends AR once: saves the layout, releases the scene, puts the glasses back. */
    private fun leave() {
        if (leaving) return
        leaving = true
        AppLog.i("AR: leaving")
        glView.queueEvent {
            val last = renderer.currentLayout()
            work.execute { settings.lastLayout = last }
            renderer.release()
        }
        work.execute { glasses.leave() }
        if (!isFinishing) finish()
    }

    // MARK: - Renderer.Listener (GL thread)

    override fun onFocus(index: Int) {
        main.post {
            focusedIndex = index
            updateHud()
        }
    }

    override fun onTracking(tracking: Boolean) {
        main.post {
            this.tracking = tracking
            AppLog.i("AR: head tracking " + if (tracking) "on" else "off (no samples: the D-pad turns the view)")
            if (tracking && settings.axes == null && !calibrating) startCalibration()
            updateHud()
        }
    }

    // MARK: - Keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
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
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isTracking && !event.isCanceled) ok()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (panelOpen) closePanel() else leave()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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
            calibrationStep()
            return
        }
        if (panelOpen) {
            panel.focusedItem()?.let { act(it.id) }
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
        panelOpen = true
        drawPanel()
    }

    private fun closePanel() {
        panelOpen = false
        calibrating = false
        glView.queueEvent { renderer.hidePanel() }
    }

    private fun drawPanel() {
        val header = panelHeader
        // PanelLayout's rows are immutable lists: the GL thread can draw them while the panel moves on.
        val rows = panel.rows
        val row = panel.row
        val col = panel.col
        glView.queueEvent { renderer.showPanel({ it.draw(rows, row, col, header, emptyMap()) }) }
    }

    private fun panelSections(): List<Pair<String, List<PanelLayout.Item>>> {
        val layoutItems = ArrayList<PanelLayout.Item>()
        layoutItems.add(PanelLayout.Item("recentre", "Re-centre"))
        for (name in Presets.NAMES) layoutItems.add(PanelLayout.Item("preset:$name", Presets.label(name)))
        val view = listOf(
            PanelLayout.Item("size-", "Smaller", percent(settings.size)),
            PanelLayout.Item("size+", "Bigger"),
            PanelLayout.Item("refresh", if (settings.refresh60) "60 Hz" else "120 Hz", "in AR"),
            PanelLayout.Item("predict", "Prediction", "${settings.predictMs} ms"),
            PanelLayout.Item("calibrate", "Calibrate", if (settings.axes == null) "not yet" else ""),
        )
        return listOf("Layout" to layoutItems, "View" to view, "" to listOf(PanelLayout.Item("leave", "Leave AR")))
    }

    private fun percent(f: Float) = "${(f * 100).toInt()} %"

    private fun act(id: String) {
        when {
            id == "recentre" -> {
                recentre()
                closePanel()
            }
            id.startsWith("preset:") -> {
                val l = Presets.layout(id.removePrefix("preset:")) ?: return
                setLayout(l)
                closePanel()
            }
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
            id == "leave" -> leave()
        }
    }

    private fun setLayout(l: Layout) {
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
        panel.set(listOf("" to listOf(PanelLayout.Item("calibrate-ok", "OK", "looking ahead"))))
        panelOpen = true
        drawPanel()
    }

    private fun calibrationStep() {
        val s = SensorSource.Sample().also { sensors.read(it) }
        val q = Quat(s.w, s.x, s.y, s.z).normalize()
        val ahead = calibration
        if (ahead == null) {
            calibration = q
            panelHeader = "Now look up (about 30°), then press OK"
            panel.set(listOf("" to listOf(PanelLayout.Item("calibrate-ok", "OK", "looking up"))))
            drawPanel()
            return
        }
        val axes = AxisCalibration.fromRotations(ahead, q)
        if (axes == null) {
            calibration = null
            panelHeader = "That didn't work: look straight ahead, then press OK"
            panel.set(listOf("" to listOf(PanelLayout.Item("calibrate-ok", "OK", "try again"))))
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

    private fun updateHud() {
        val parts = ArrayList<String>()
        parts.add(clock.format(Date()))
        battery()?.let { parts.add(it) }
        if (!tracking) parts.add("no head tracking")
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
        private const val TURN_STEP = 5f
        private const val NUDGE_STEP = 5f
    }
}
