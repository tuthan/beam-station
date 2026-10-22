package io.github.tuthan.beamstation

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The app's settings (its icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the choice of ours or Original (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
class SettingsActivity : Activity() {
    private lateinit var view: SettingsView
    private var preview: DreamView? = null   // "Screensaver": the screensaver right here, without changing the system setting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Watchdog.install(this)
        Ui.init(this)
        view = SettingsView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.resume()
        // From ADB: am start -n io.github.tuthan.beamstation/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && intent.getBooleanExtra("screensaver", false)) {
            intent.removeExtra("screensaver")
            view.pause()
            showPreview()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        closePreview()     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause()
        Xgimi.paused(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Xgimi.focusChanged(this, hasFocus)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) closePreview()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }
        if (view.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    fun showPreview() {
        val p = DreamView(this)
        preview = p
        setContentView(p)
        p.start()
    }

    private fun closePreview() {
        val p = preview ?: return
        p.stop()
        preview = null
        setContentView(view)
        view.resume()
    }

    internal class SettingsView(private val activity: SettingsActivity) : View(activity), Choreographer.FrameCallback {
        private val scene = Scene()
        private val handler = Handler(Looper.getMainLooper())
        private val pop = Array(12) { Spring(0.28f, 0.85f, 0f) }
        // The list picker (a city or a time zone): what it shows, which one is in use, where we are.
        private val pickLabels = ArrayList<String>()
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private val shelfRows = ArrayList<Array<String>>()
        private val shelfOrder = ArrayList<String>()
        private var pickFor = 0   // pickFor: PICK_CITY or PICK_ZONE
        private var pickCurrent = 0
        private var pickScroll = 0
        // Time Zone (XGIMI projectors): the zones the picker lists (the city's first), and the one being set.
        private val zones = ArrayList<String>()
        private var cityZone: String? = null   // cityZone: the city's, from its forecast (null if not known yet)
        private var zoneSetting: String? = null
        private var zoneFailed = false
        private val cities = ArrayList<Array<String>>()
        private var cityStatus = ""
        private val tmp = RectF()
        private val rowRect = RectF()
        private val focusBox = RectF()
        private var state = JSONObject()
        private var recs = JSONObject()
        private var hasKey = false
        private var screen = MAIN
        private var focus = 0
        private var running = false
        private var okDown = false
        private var backDown = false
        private var wasBusy = false
        private var mainRows = mainRows(false)
        // Wi-Fi (XGIMI projectors, Rokid Station): what the TV sees, the line under the list, the network picked to forget.
        private var wifi: Wifi.Status? = null
        private var wifiLine = ""
        private var wifiPicked: Wifi.Network? = null
        private var wifiBusy = false
        // Bluetooth (XGIMI projectors, Rokid Station): paired devices, the ones the search found, and the device being paired or picked.
        private var btPaired: List<Bluetooth.Device> = ArrayList()
        private val btFound = ArrayList<Bluetooth.Device>()
        private var btPicked: Bluetooth.Device? = null
        private var btPairing: Bluetooth.Device? = null
        private var btSearching = false
        private var btListening = false
        private var btLine = ""
        private var a2dp: BluetoothProfile? = null
        // Picture (XGIMI's Play 6): the projector's settings and what a move does (null until read), and a change on its way.
        private var picture: Picture.State? = null
        private var moves: Xgimi.Moves? = null
        private var game = -1   // HDMI game mode (Xgimi.GAME_*), -1 until read
        private var pictureLine = ""
        private var pictureChanges = 0   // changes on their way: only the last one's answer is shown
        // Glasses (Rokid Station): the glasses' settings (null while none are plugged in), and a change on its way.
        private var glasses: Rokid.State? = null
        private var glassesLine = ""
        private var glassesChanges = 0
        // Power (Rokid Station): the battery, the timeouts (null until read), when the sleep timer goes off, a change on its way.
        private var battery: Power.Battery? = null
        private var timeouts: Power.Timeouts? = null
        private var timerEnd = 0L
        private var powerLine = ""
        private var powerChanges = 0
        // ADB and Bluetooth calls, one at a time; the thread ends after 30 s without work (nothing to shut down).
        private val work: ExecutorService = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
        private var errorLayout: StaticLayout? = null   // the last error, wrapped (rebuilt only when it changes)
        private var errorShown: String? = null
        private var adbLayout: StaticLayout? = null   // the Home Screen page's ADB line (XGIMI), wrapped
        private var adbShown: String? = null
        private var versionText: String? = null   // read once: the package manager isn't asked on every frame
        private val spoken: Spoken
        private var startedAt = 0L
        private var lastNanos = 0L
        private var pendingAt = 0L
        private var lastSky = 0L
        private var w = 0
        private var h = 0
        private var k = 1f
        private lateinit var title: Paint
        private lateinit var subtitle: Paint
        private lateinit var version: Paint
        private lateinit var rowLabel: Paint
        private lateinit var rowValue: Paint
        private lateinit var rowSub: Paint
        private lateinit var header: Paint
        private lateinit var cellLabel: Paint
        private lateinit var cellValue: Paint
        private lateinit var warn: Paint
        private lateinit var spinner: Paint
        private lateinit var white: Paint
        private lateinit var shadow: Paint
        private lateinit var notice: TextPaint

        private val poll: Runnable = object : Runnable {
            override fun run() {
                if (!running) return
                state = HomeMode.state(context)
                hasKey = Recs.hasKey(context)
                recs = Recs.status(context)
                val nowBusy = !busyText().isEmpty()
                if (nowBusy) pendingAt = 0
                // A switch just ended: TalkBack reads how it went (the newest notice).
                val notices: JSONArray? = state.optJSONArray("notices")
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    val newest: JSONObject? = notices.optJSONObject(0)
                    if (newest != null) announceForAccessibility(newest.optString("text"))
                }
                wasBusy = nowBusy
                invalidate()
                handler.postDelayed(this, if (busy()) 500L else 2000L)
            }
        }

        /** While the Wi-Fi screen is open: a new scan every 15 s. */
        private val wifiScan: Runnable = object : Runnable {
            override fun run() {
                if (!running || screen != WIFI) return
                refreshWifi(true)
                handler.postDelayed(this, 15_000)
            }
        }

        /** While the Bluetooth screen is open: what the search finds, pairing, connections. */
        private val btEvents: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent) {
                val action = i.action
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                when (action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        if (d != null && d.bondState != BluetoothDevice.BOND_BONDED) {
                            val dev = Bluetooth.of(d, false)
                            if (dev != null) {
                                dev.rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                addFound(dev)
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> btSearching = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> btSearching = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        val p = btPairing
                        if (p != null && d != null && d.address == p.address) {
                            if (state == BluetoothDevice.BOND_BONDED) {
                                btLine = Ui.tr("Pareado com ", "Paired with ") + p.name + "."
                                btPairing = null
                            } else if (state == BluetoothDevice.BOND_NONE) {
                                btLine = Ui.tr("Não deu para parear com ", "Couldn't pair with ") + p.name +
                                    Ui.tr(". Coloque-o no modo de pareamento e tente de novo.", ". Put it in pairing mode and try again.")
                                btPairing = null
                            }
                        }
                        refreshBt()
                    }
                    else -> refreshBt()   // connected or disconnected
                }
                invalidate()
            }
        }

        init {
            val wx = HomeData.get(activity).weather
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset)
            scene.setMotion(0.5f, 0.2f)
            scene.applyPalette()
            setFocusable(true)
            contentDescription = Config.APP_NAME
            // TalkBack: one item per row, id = row + 1.
            spoken = Spoken(this, object : Spoken.Source {
                override fun items(out: MutableList<Int>) {
                    for (i in 1..rowCount()) out.add(i)
                }

                override fun text(id: Int): String = if (id >= 1 && id <= rowCount()) rowText(id - 1) else ""

                override fun bounds(id: Int, out: Rect): Boolean {
                    if (id < 1 || id > rowCount()) return false
                    rowBox(id - 1).round(out)
                    return true
                }

                override fun focused(): Int = focus + 1

                override fun focus(id: Int) {
                    focus = id - 1
                    invalidate()
                }

                override fun click(id: Int) {
                    activate()
                }
            })
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = spoken

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private fun rowText(row: Int): String {
            return when (screen) {
                MAIN -> {
                    if (row == 0) {
                        val busyText = busyText()
                        val value = if (busy()) (if (busyText.isEmpty()) Ui.tr("Começando…", "Starting…") else busyText)
                            else if (freeActive()) Config.APP_NAME else Ui.tr("Original", "Original")
                        return Ui.tr("Início, ", "Home Screen, ") + value
                    }
                    if (mainRow(row) == WIFI_ROW) return Ui.tr("Wi-Fi", "Wi-Fi") + ", " + wifiSummary()
                    if (mainRow(row) == BT_ROW) return "Bluetooth" + (if (btSummary().isEmpty()) "" else ", " + btSummary())
                    if (mainRow(row) == PICTURE_ROW) return Ui.tr("Imagem", "Picture") + (if (pictureSummary().isEmpty()) "" else ", " + pictureSummary())
                    if (mainRow(row) == ZONE_ROW) return Ui.tr("Fuso Horário", "Time Zone") + ", " + zoneSummary()
                    if (mainRow(row) == GLASSES_ROW) return Ui.tr("Óculos", "Glasses") + (if (glassesSummary().isEmpty()) "" else ", " + glassesSummary())
                    if (mainRow(row) == POWER_ROW) return Ui.tr("Energia", "Power") + (if (powerSummary().isEmpty()) "" else ", " + powerSummary())
                    val names = arrayOf(Ui.tr("Tela de Descanso, ver agora", "Screensaver, preview"), Ui.tr("Ajustes do Início", "Home Settings"),
                        Ui.tr("Prateleira", "Shelf"), Ui.tr("Ajustes da TV", "TV Settings"))
                    val at = mainRow(row) - 1
                    if (at < names.size) names[at] else ""
                }
                WIFI -> {
                    val at = pickScroll + row
                    if (at == 0) return Ui.tr("Wi-Fi", "Wi-Fi") + ", " + wifiOnOff()
                    val n = wifiNet(at) ?: return ""
                    val value = netValue(n)
                    n.ssid + (if (value.isEmpty()) "" else ", " + value) + ", " + at + Ui.tr(" de ", " of ") + wifi!!.networks.size
                }
                WIFI_FORGET -> {
                    val rows = forgetRows()
                    if (row < rows.size) rows[row][0] + ", " + rows[row][1] else ""
                }
                BT -> {
                    val at = pickScroll + row
                    if (at == 0) return Ui.tr("Parear um aparelho novo", "Pair a new device") + (if (btSearching) ", " + Ui.tr("Procurando…", "Searching…") else "")
                    val d = btItem(at) ?: return ""
                    val value = btValue(d)
                    d.name + (if (value.isEmpty()) "" else ", " + value)
                }
                BT_DEVICE -> {
                    val rows = btActions()
                    if (row < rows.size) rows[row][0] + ", " + rows[row][1] else ""
                }
                HOME_CHOICE -> {
                    val name = if (row == 0) Config.APP_NAME + ", " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                        else "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen")
                    val inUse = (row == 0) == freeActive()
                    name + (if (inUse) Ui.tr(". Em uso", ". In use") else "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2")
                }
                HOME_SETTINGS,
                PICTURE,
                GLASSES,
                POWER -> {
                    val labels = listLabels()
                    val values = listValues()
                    if (row < labels.size) labels[row] + ", " + values[row] else ""
                }
                PICKER -> {
                    val at = pickScroll + row
                    if (at >= pickLabels.size) return ""
                    pickLabels[at] + (if (at == pickCurrent) Ui.tr(", escolhido", ", selected") else "") +
                        ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size
                }
                SHELF_EDIT -> {
                    val at = pickScroll + row
                    if (at >= shelfRows.size) return ""
                    val app = shelfRows[at]
                    val place = shelfOrder.indexOf(app[0])
                    app[1] + ", " + (if (place >= 0) Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                        else Ui.tr("fora da prateleira", "not on the shelf"))
                }
                else -> ""
            }
        }

        private fun rowBox(row: Int): RectF {
            val top: Float
            val height: Float
            if (screen == MAIN) {
                top = mainTop() + row * (ROW_H + ROW_GAP)
                height = ROW_H
            } else if (screen == HOME_CHOICE || screen == WIFI_FORGET || screen == BT_DEVICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP)
                height = CHOICE_H
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP)
                height = LIST_ROW_H
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k)
            return focusBox
        }

        fun resume() {
            if (running) return
            running = true
            startedAt = SystemClock.uptimeMillis()
            lastNanos = 0
            handler.post(poll)
            if (screen == WIFI) handler.post(wifiScan)
            else if (Wifi.available()) refreshWifi(false)
            if (screen == BT || screen == BT_DEVICE) openBt()
            else if (Bluetooth.available()) refreshBt()
            if (Picture.available()) refreshPicture()
            if (Rokid.available()) refreshGlasses()
            if (Power.available()) refreshPower()
            Choreographer.getInstance().postFrameCallback(this)
            spoken.readAgain()
        }

        fun pause() {
            running = false
            handler.removeCallbacks(poll)
            handler.removeCallbacks(wifiScan)
            closeBt()
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            var moving = busy()
            for (s in pop) moving = moving or !s.settled()
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            w = width
            h = height
            k = w / 1920f
            scene.setSize(w, h)
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF.toInt())
            title.textAlign = Paint.Align.CENTER
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF.toInt())
            subtitle.textAlign = Paint.Align.CENTER
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF)
            version.textAlign = Paint.Align.CENTER
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF.toInt())
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF.toInt())
            rowValue.textAlign = Paint.Align.RIGHT
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF)
            header.letterSpacing = 0.06f
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF.toInt())
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF.toInt())
            cellValue.textAlign = Paint.Align.RIGHT
            warn = Ui.text(Ui.regular, 23 * k, AMBER)
            notice = TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF.toInt()))
            spinner = Paint(Paint.ANTI_ALIAS_FLAG)
            spinner.style = Paint.Style.STROKE
            spinner.strokeCap = Paint.Cap.ROUND
            spinner.strokeWidth = 3.5f * k
            white = Paint(Paint.ANTI_ALIAS_FLAG)
            white.color = 0xFFF5F5F7.toInt()
            shadow = Paint(Paint.ANTI_ALIAS_FLAG)
            shadow.maskFilter = BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL)
        }

        private fun busy(): Boolean {
            return !busyText().isEmpty() || (pendingAt != 0L && SystemClock.uptimeMillis() - pendingAt < 10_000)
        }

        private fun busyText(): String {
            val at = state.optLong("busyAt")
            val now = SystemClock.elapsedRealtime()
            val stale = now < at || now - at > 10 * 60_000L
            return if (stale) "" else state.optString("busy")
        }

        private fun freeActive(): Boolean {
            return HomeMode.FREE == state.optString("mode") && !state.optBoolean("homeReverted")
        }

        /**
         * Picture and Time Zone on XGIMI projectors; Wi-Fi and Bluetooth on XGIMI projectors and the Rokid Station;
         * Glasses while Rokid glasses are plugged in; Power on the Rokid Station; all before TV Settings.
         */
        private fun mainRows(glasses: Boolean): IntArray {
            val rows = ArrayList(listOf(0, 1, 2, 3))
            if (Picture.available()) rows.add(PICTURE_ROW)
            if (Wifi.available()) rows.add(WIFI_ROW)
            if (Bluetooth.available()) rows.add(BT_ROW)
            if (HomeMode.XGIMI) rows.add(ZONE_ROW)
            if (glasses) rows.add(GLASSES_ROW)
            if (Power.available()) rows.add(POWER_ROW)
            rows.add(TV_ROW)
            val out = IntArray(rows.size)
            for (i in out.indices) out[i] = rows[i]
            return out
        }

        /**
         * Where the main screen's rows start: higher past seven rows (on XGIMI projectors one row at most), so what's under
         * them (the TMDB credit) stays on screen.
         */
        private fun mainTop(): Float {
            val up = max(0, mainRows.size - 7)
            return LIST_TOP - (if (HomeMode.XGIMI) min(1, up) else up) * (ROW_H + ROW_GAP)
        }

        /** The main screen's row at position i. */
        private fun mainRow(i: Int): Int {
            return mainRows[i]
        }

        /** Where a main screen row is (0 if it isn't shown). */
        private fun mainIndex(row: Int): Int {
            for (i in mainRows.indices) if (mainRows[i] == row) return i
            return 0
        }

        private fun rowCount(): Int {
            return when (screen) {
                MAIN -> mainRows.size
                HOME_CHOICE,
                WIFI_FORGET -> 2
                WIFI -> min(PICK_VISIBLE, 1 + wifiCount())
                BT -> min(PICK_VISIBLE, 1 + btCount())
                BT_DEVICE -> btActions().size
                HOME_SETTINGS,
                PICTURE,
                GLASSES,
                POWER -> listLabels().size
                PICKER -> min(PICK_VISIBLE, pickLabels.size)
                SHELF_EDIT -> min(PICK_VISIBLE, shelfRows.size)
                else -> 0
            }
        }

        override fun onDraw(c: Canvas) {
            if (w == 0) return
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 1 / 60f else min(0.05f, (now - lastNanos) / 1e9f)
            lastNanos = now
            for (i in pop.indices) {
                pop[i].target = if (i == focus && i < rowCount()) 1f else 0f
                pop[i].step(dt)
            }
            val up = SystemClock.uptimeMillis()
            val seconds = (up - startedAt) / 1000f
            if (up - lastSky >= 100) {
                lastSky = up
                scene.advance(seconds)
            }
            scene.drawBackground(c)

            // On the left: big symbol, title, description.
            val cx = 500 * k
            Symbols.draw(context, c, "mark", cx, 400 * k, 150 * k, 0xEBFFFFFF.toInt())
            c.drawText(Config.APP_NAME, cx, 612 * k, title)
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle)
            c.drawText(versionText ?: versionName(context).also { versionText = it }, cx, 704 * k, version)

            when (screen) {
                MAIN -> drawMain(c, seconds)
                HOME_CHOICE -> drawChoice(c)
                PICKER -> drawPicker(c)
                SHELF_EDIT -> drawShelfEditor(c)
                WIFI -> drawWifi(c)
                WIFI_FORGET -> drawForget(c)
                BT -> drawBt(c)
                BT_DEVICE -> drawBtDevice(c)
                else -> drawList(c)
            }
        }

        private fun drawMain(c: Canvas, seconds: Float) {
            val working = busy()
            val busyText = busyText()
            val mode = if (freeActive()) Config.APP_NAME else Ui.tr("Original", "Original")
            val labels = arrayOf(Ui.tr("Início", "Home Screen"), Ui.tr("Tela de Descanso", "Screensaver"),
                Ui.tr("Ajustes do Início", "Home Settings"), Ui.tr("Prateleira", "Shelf"),
                Ui.tr("Ajustes da TV", "TV Settings"), Ui.tr("Wi-Fi", "Wi-Fi"), "Bluetooth",
                Ui.tr("Imagem", "Picture"), Ui.tr("Fuso Horário", "Time Zone"), Ui.tr("Óculos", "Glasses"), Ui.tr("Energia", "Power"))
            val values = arrayOf(if (working) "" else mode, Ui.tr("Ver agora", "Preview"), "", "", "", if (Wifi.available()) wifiSummary() else "", btSummary(),
                pictureSummary(), if (HomeMode.XGIMI) zoneSummary() else "", glassesSummary(), powerSummary())
            for (i in 0 until rowCount()) {
                val row = mainRow(i)
                val y = (mainTop() + i * (ROW_H + ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k)
                val f = drawRowBackground(c, i)
                val ink = blend(0xF2FFFFFF.toInt(), INK, f)
                rowLabel.color = ink
                val base = rowRect.centerY() + Ui.halfCap(rowLabel)
                c.drawText(labels[row], rowRect.left + 30 * k, base, rowLabel)
                val right = rowRect.right - 30 * k
                if (row >= 2) {
                    val cw = Symbols.width(context, "chevron_right", 22 * k)
                    Symbols.draw(context, c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E.toInt(), f))
                    if (!values[row].isEmpty()) {
                        rowValue.color = blend(0x8CFFFFFF.toInt(), 0x8C1C1C1E.toInt(), f)
                        c.drawText(TextUtils.ellipsize(values[row], TextPaint(rowValue), 420 * k, TextUtils.TruncateAt.END).toString(),
                            right - cw - 18 * k, base, rowValue)
                    }
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    val text = if (busyText.isEmpty()) Ui.tr("Começando…", "Starting…") else busyText
                    rowValue.color = blend(0xB3FFFFFF.toInt(), 0xB31C1C1E.toInt(), f)
                    val shown = TextUtils.ellipsize(text, TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString()
                    c.drawText(shown, right, base, rowValue)
                    val sx = right - rowValue.measureText(shown) - 26 * k
                    spinner.color = blend(0xFFFFFFFF.toInt(), INK, f)
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner)
                } else {
                    rowValue.color = blend(0x8CFFFFFF.toInt(), 0x8C1C1C1E.toInt(), f)
                    c.drawText(values[row], right, base, rowValue)
                }
            }

            var y = (mainTop() + rowCount() * (ROW_H + ROW_GAP) + 20) * k
            val error = state.optString("error")
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow") || busyText.contains("разрешите"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                    LIST_X * k + 30 * k, y, warn)
                y += 46 * k
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                val width = Math.round(760 * k)
                var layout = errorLayout
                if (layout == null || error != errorShown || layout.width != width) {
                    errorShown = error
                    val built: StaticLayout = StaticLayout.Builder.obtain(error, 0, error.length, TextPaint(warn), width)
                        .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build()
                    layout = built
                    errorLayout = built
                }
                c.save()
                c.translate(LIST_X * k + 30 * k, y - layout.getLineBaseline(0))
                layout.draw(c)
                c.restore()
                y += layout.getLineBaseline(layout.lineCount - 1) - layout.getLineBaseline(0) + 46 * k
            }
            if (!HomeMode.XGIMI || mainRows.size <= 8) y = drawComparison(c, y)   // XGIMI past eight rows: no room for it
            y = drawRecs(c, y + 26 * k)
            drawNotices(c, y + 40 * k)
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private fun drawRecs(c: Canvas, y: Float): Float {
            val x = LIST_X * k + 30 * k
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header)
            val error = recs.optString("error")
            val count = recs.optInt("count")
            val line = if (!hasKey) Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).")
                else if (!error.isEmpty()) Ui.tr("Erro: ", "Error: ") + error + "."
                else if (count > 0) Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.")
                else Ui.tr("Buscando os títulos…", "Fetching titles…")
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice)
            version.textAlign = Paint.Align.LEFT
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version)
            if (!Config.WATCHMODE_KEY.trim { it <= ' ' }.isEmpty()) {
                c.drawText(Ui.tr("Links dos títulos por Watchmode.", "Links to the titles by Watchmode."), x, y + 108 * k, version)
            }
            version.textAlign = Paint.Align.CENTER
            return y + (if (Config.WATCHMODE_KEY.trim { it <= ' ' }.isEmpty()) 78 else 108) * k
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private fun drawRowBackground(c: Canvas, i: Int): Float {
            val f = Ui.clamp(pop[i].value)
            if (f > 0.01f) {
                val s = 1 + 0.02f * f
                val cx = rowRect.centerX()
                val cy = rowRect.centerY()
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2)
                val r = 18 * k * s
                tmp.offset(0f, 10 * k * f)
                shadow.color = Ui.alpha(0xFF000000.toInt(), 0.4f * f)
                c.drawRoundRect(tmp, r, r, shadow)
                tmp.offset(0f, -10 * k * f)
                white.alpha = Math.round(255 * f)
                c.drawRoundRect(tmp, r, r, white)
                rowRect.set(tmp)
            }
            return f
        }

        private fun drawComparison(c: Canvas, y: Float): Float {
            val x = LIST_X * k + 30 * k
            val colA = LIST_R * k - 220 * k
            val colB = LIST_R * k - 30 * k
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header)
            cellValue.color = 0x73FFFFFF
            c.drawText(Ui.tr("Original", "Original"), colA, y, cellValue)
            c.drawText(Config.APP_NAME, colB, y, cellValue)
            cellValue.color = 0xD9FFFFFF.toInt()
            val metrics: JSONObject? = state.optJSONObject("metrics")
            val a = metrics?.optJSONObject(HomeMode.ORIGINAL)
            val b = metrics?.optJSONObject(HomeMode.FREE)
            val rows = arrayOf(arrayOf(Ui.tr("Botão Início", "Home button"), "homeMs"), arrayOf(Ui.tr("Memória livre", "Free memory"), "memAvailableMB"),
                arrayOf(Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"))
            for (i in rows.indices) {
                val base = y + (46 + 40 * i) * k
                c.drawText(rows[i][0], x, base, cellLabel)
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue)
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue)
            }
            return y + (46 + 40 * rows.size) * k
        }

        private fun drawNotices(c: Canvas, y: Float) {
            val x = LIST_X * k + 30 * k
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header)
            val notices: JSONArray? = state.optJSONArray("notices")
            var shown = 0
            var i = 0
            while (notices != null && i < notices.length() && shown < 2) {
                val o: JSONObject? = notices.optJSONObject(i)
                i++
                if (o == null) continue
                val line = ago(o.optLong("at")) + " · " + (if (Ui.ru) Russian.text(o.optString("text")) else o.optString("text"))
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice)
                shown++
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice)
        }

        /** Home screen choice: ours or the original, with a check on the one in use. */
        private fun drawChoice(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val options = arrayOf(arrayOf(Config.APP_NAME, Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")),
                arrayOf(Ui.tr("Original", "Original"), Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")))
            val current = if (freeActive()) 0 else 1
            for (i in 0 until 2) {
                val y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(0xF2FFFFFF.toInt(), INK, f)
                rowSub.color = blend(0x8CFFFFFF.toInt(), 0x991C1C1E.toInt(), f)
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel)
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub)
                if (i == current) {
                    val cw = Symbols.width(context, "check", 26 * k)
                    Symbols.draw(context, c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF.toInt(), INK, f))
                }
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint)
            // XGIMI ships ro.adb.secure=0 (read by HomeMode during the switch): its ADB asks no one.
            if (HomeMode.XGIMI && "0" == state.optString("adbSecure")) {
                val text = Ui.tr("Este projetor deixa qualquer computador da rede se conectar sem perguntar.",
                    "This projector lets any computer on the network connect without asking.")
                val width = Math.round(760 * k)
                var layout = adbLayout
                if (layout == null || text != adbShown || layout.width != width) {
                    adbShown = text
                    val built: StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(warn), width)
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
                    layout = built
                    adbLayout = built
                }
                c.save()
                c.translate(LIST_X * k + 30 * k, (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 86) * k - layout.getLineBaseline(0))
                layout.draw(c)
                c.restore()
            }
        }

        private fun blend(a: Int, b: Int, f: Float): Int {
            val aa = a ushr 24
            val ar = (a shr 16) and 255
            val ag = (a shr 8) and 255
            val ab = a and 255
            val ba = b ushr 24
            val br = (b shr 16) and 255
            val bg = (b shr 8) and 255
            val bb = b and 255
            return (Math.round(aa + (ba - aa) * f) shl 24) or (Math.round(ar + (br - ar) * f) shl 16) or
                (Math.round(ag + (bg - ag) * f) shl 8) or Math.round(ab + (bb - ab) * f)
        }

        private fun metric(m: JSONObject?, key: String): String {
            if (m == null || !m.has(key)) return "—"
            val v = m.optLong(key)
            if (key == "homeMs") {
                val unit = if (Ui.ru) " мс" else " ms"
                return "$v$unit"
            }
            if (key == "memAvailableMB" && v >= 1024) return java.lang.String.format(Ui.locale(), if (Ui.ru) "%.2f ГБ" else "%.2f GB", v / 1024.0)
            val unit = if (Ui.ru) " МБ" else " MB"
            return "$v$unit"
        }

        private fun ago(at: Long): String {
            val minutes = max(0L, (System.currentTimeMillis() - at) / 60_000)
            if (minutes < 1) return Ui.tr("agora", "now")
            if (minutes < 60) return Ui.tr("há $minutes min", "$minutes min ago")
            if (minutes < 48 * 60) return Ui.tr("há ${minutes / 60} h", "${minutes / 60} h ago")
            return Ui.tr("há ${minutes / 1440} dias", "${minutes / 1440} days ago")
        }

        private fun versionName(c: Context): String {
            return try {
                Config.APP_NAME + " " + c.packageManager.getPackageInfo(c.packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                Config.APP_NAME
            }
        }

        fun onKey(e: KeyEvent): Boolean {
            val down = e.action == KeyEvent.ACTION_DOWN
            return when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (down && focus > 0) focus--
                    else if (down && scrolling() && pickScroll > 0) pickScroll--
                    invalidate()
                    spoken.focusMoved()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && focus < rowCount() - 1) focus++
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++
                    invalidate()
                    spoken.focusMoved()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        invalidate()
                    } else if (down && (screen == HOME_SETTINGS || screen == PICTURE || screen == GLASSES
                            || screen == POWER)) {
                        adjust(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        invalidate()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down) {
                        if (e.repeatCount == 0) okDown = true
                    } else {
                        if (okDown && !e.isCanceled && spoken.keyClick()) activate()
                        okDown = false
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (screen == MAIN) return false   // leaves the settings
                    if (down) {
                        if (e.repeatCount == 0) backDown = true
                    } else {
                        if (backDown && !e.isCanceled) {
                            if (screen == PICKER && pickFor == PICK_CITY) {
                                focus = CITY_ROW
                                screen = HOME_SETTINGS
                            } else if (screen == PICKER && pickFor == PICK_ZONE) {
                                focus = mainIndex(ZONE_ROW)
                                screen = MAIN
                            } else if (screen == WIFI_FORGET) {
                                screen = WIFI
                                keepWifiFocus(wifiPicked!!.ssid)
                            } else if (screen == BT_DEVICE) {
                                screen = BT
                                keepBtFocus(btPicked!!.address)
                            } else {
                                if (screen == BT) closeBt()
                                focus = mainIndex(if (screen == HOME_SETTINGS) 2 else if (screen == SHELF_EDIT) 3
                                    else if (screen == WIFI) WIFI_ROW else if (screen == BT) BT_ROW
                                    else if (screen == PICTURE) PICTURE_ROW else if (screen == GLASSES) GLASSES_ROW
                                    else if (screen == POWER) POWER_ROW else 0)
                                screen = MAIN
                            }
                        }
                        backDown = false
                    }
                    invalidate()
                    spoken.focusMoved()
                    true
                }
                else -> false
            }
        }

        // MARK: - Home Settings

        private fun dollar(): Boolean {
            return "BRL".equals(Config.CURRENCY.trim { it <= ' ' }, ignoreCase = true)
        }

        private fun settingLabels(): Array<String> {
            val l = ArrayList<String>()
            l.add(Ui.tr("Idioma", "Language"))
            l.add(Ui.tr("Temperatura", "Temperature"))
            l.add(Ui.tr("Tema", "Theme"))
            l.add(Ui.tr("Sons de navegação", "Navigation sounds"))
            l.add(Ui.tr("Widget do clima", "Weather widget"))
            l.add(Ui.tr("Cidade", "City"))
            l.add(Ui.tr("Widget de cotações", "Quotes widget"))
            l.add("Bitcoin")
            l.add("Ethereum")
            l.add("Solana")
            if (dollar()) l.add(Ui.tr("Dólar", "Dollar"))
            return l.toTypedArray()
        }

        private fun settingValues(): Array<String> {
            val labels = settingLabels()
            val v = Array(labels.size) { "" }
            val lang = Prefs.language()
            v[0] = if (lang == "pt") "Português" else if (lang == "en") "English" else if (lang == "es") "Español"
                else if (lang == "de") "Deutsch" else if (lang == "ru") "Русский" else Ui.tr("Automático", "Automatic")
            val unit = Prefs.temperature()
            v[1] = if (unit == "C") "°C" else if (unit == "F") "°F" else Ui.tr("Automática", "Automatic")
            val theme = Prefs.theme()
            v[2] = if (theme == "light") Ui.tr("Claro", "Light") else if (theme == "dark") Ui.tr("Escuro", "Dark") else Ui.tr("Automático", "Automatic")
            v[3] = onOff(Prefs.sounds())
            v[4] = onOff(Prefs.weather())
            v[CITY_ROW] = if (!cityStatus.isEmpty()) cityStatus else if (Prefs.city().isEmpty()) "—" else Prefs.city()
            v[6] = onOff(Prefs.quotes())
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (i in COIN_ROWS until v.size) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS))
            return v
        }

        private fun onOff(on: Boolean): String {
            return if (on) Ui.tr("Ligado", "On") else Ui.tr("Desligado", "Off")
        }

        /** The rows of the list screens: Home Settings, Picture, Glasses, Power. */
        private fun listLabels(): Array<String> {
            return if (screen == PICTURE) pictureLabels() else if (screen == GLASSES) glassesLabels()
                else if (screen == POWER) powerLabels() else settingLabels()
        }

        private fun listValues(): Array<String> {
            return if (screen == PICTURE) pictureValues() else if (screen == GLASSES) glassesValues()
                else if (screen == POWER) powerValues() else settingValues()
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private fun adjust(dir: Int) {
            if (screen == PICTURE) {
                adjustPicture(dir)
                return
            }
            if (screen == GLASSES) {
                adjustGlasses(dir)
                return
            }
            if (screen == POWER) {
                adjustPower(dir)
                return
            }
            when (focus) {
                0 -> {
                    val at = LANGUAGES.indexOf(Prefs.language())
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.size) % LANGUAGES.size])
                }
                1 -> {
                    val at = UNITS.indexOf(Prefs.temperature())
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.size) % UNITS.size])
                }
                2 -> {
                    val at = THEMES.indexOf(Prefs.theme())
                    Prefs.setTheme(THEMES[(at + dir + THEMES.size) % THEMES.size])
                }
                3 -> Prefs.setSounds(!Prefs.sounds())
                4 -> Prefs.setWeather(!Prefs.weather())
                CITY_ROW -> return
                6 -> Prefs.setQuotes(!Prefs.quotes())
                else -> {
                    if (!Prefs.quotes()) return
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS))
                }
            }
            HomeData.get(context).settingsChanged()
        }

        private fun askCity() {
            if (Ui.tr("Procurando…", "Searching…") == cityStatus) return
            val field = EditText(activity)
            field.setSingleLine(true)
            field.hint = Ui.tr("Digite o nome da cidade", "Type the city's name")
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Cidade", "City"))
                .setView(field)
                .setPositiveButton(Ui.tr("Buscar", "Search")) { _, _ -> searchCity(field.text.toString()) }
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create()
            field.setOnEditorActionListener { _, _, _ ->
                dialog.dismiss()
                searchCity(field.text.toString())
                true
            }
            dialog.show()
        }

        private fun searchCity(name: String) {
            if (name.trim { it <= ' ' }.isEmpty()) return
            cityStatus = Ui.tr("Procurando…", "Searching…")
            invalidate()
            Thread {
                var found: List<Array<String>>
                var error: String
                try {
                    found = HomeData.findCity(name)
                    error = if (found.isEmpty()) Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") else ""
                } catch (e: Exception) {
                    found = ArrayList()
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.message
                    AppLog.w("city search: " + e.message)
                }
                val result = found
                val message = error
                handler.post {
                    cityStatus = message
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear()
                        cities.addAll(result)
                        openPicker(PICK_CITY)
                        spoken.focusMoved()
                    }
                    invalidate()
                    if (!message.isEmpty()) handler.postDelayed({
                        cityStatus = ""
                        invalidate()
                    }, 4000)
                }
            }.start()
        }

        private fun scrolling(): Boolean {
            return screen == PICKER || screen == SHELF_EDIT || screen == WIFI || screen == BT
        }

        private fun listSize(): Int {
            return if (screen == SHELF_EDIT) shelfRows.size else if (screen == WIFI) 1 + wifiCount() else if (screen == BT) 1 + btCount() else pickLabels.size
        }

        // MARK: - Picture (XGIMI's Play 6)

        private fun modeName(mode: Int): String {
            return when (mode) {
                10 -> Ui.tr("IA", "AI")
                1 -> Ui.tr("Filme", "Movie")
                9 -> Ui.tr("Esporte", "Sport")
                7 -> "TV"
                3 -> Ui.tr("Personalizado", "Custom")
                25 -> Ui.tr("Escritório", "Office")
                5 -> Ui.tr("Desempenho", "Performance")
                else -> "—"
            }
        }

        private fun motionName(level: Int): String {
            return when (level) {
                0 -> Ui.tr("Desligado", "Off")
                1 -> Ui.tr("Baixo", "Low")
                2 -> Ui.tr("Médio", "Medium")
                else -> Ui.tr("Alto", "High")
            }
        }

        /** The main row's value: the picture mode in use. */
        private fun pictureSummary(): String {
            val p = picture
            return if (p == null || Picture.indexOf(Picture.MODES, p.mode) < 0) "" else modeName(p.mode)
        }

        private fun pictureRows(): IntArray {
            val rows = ArrayList(listOf(P_MODE, P_HDR))
            val p = picture
            if (p != null && p.hasMotion()) rows.add(P_MOTION)
            if (game >= 0) rows.add(P_GAME)
            if (moves != null) {
                rows.add(P_MOVE_KEYSTONE)
                rows.add(P_MOVE_FOCUS)
            }
            val out = IntArray(rows.size)
            for (i in out.indices) out[i] = rows[i]
            return out
        }

        private fun pictureLabels(): Array<String> {
            val rows = pictureRows()
            val l = Array(rows.size) { "" }
            for (i in rows.indices) {
                l[i] = when (rows[i]) {
                    P_MODE -> Ui.tr("Modo de imagem", "Picture mode")
                    P_HDR -> "HDR"
                    P_MOTION -> Ui.tr("Suavização de movimento", "Motion smoothing")
                    P_GAME -> Ui.tr("Modo jogo (HDMI)", "Game mode (HDMI)")
                    P_MOVE_KEYSTONE -> Ui.tr("Corrigir a imagem ao mover", "Auto keystone when moved")
                    else -> Ui.tr("Focar ao mover", "Auto focus when moved")
                }
            }
            return l
        }

        private fun pictureValues(): Array<String> {
            val rows = pictureRows()
            val v = Array(rows.size) { "" }
            val p = picture
            for (i in rows.indices) {
                v[i] = when (rows[i]) {
                    P_MODE -> if (p == null) "—" else modeName(p.mode)
                    P_HDR -> if (p == null) "—" else if (p.hdr) Ui.tr("Automático", "Automatic") else Ui.tr("Desligado", "Off")
                    P_MOTION -> motionName(p!!.motion)
                    P_GAME -> if (game == Xgimi.GAME_AUTO) Ui.tr("Automático", "Automatic") else onOff(game == Xgimi.GAME_ON)
                    P_MOVE_KEYSTONE -> onOff(moves!!.keystone)
                    else -> onOff(moves!!.focus)
                }
            }
            return v
        }

        private fun refreshPicture() {
            if (pictureChanges > 0) return
            val app = context.applicationContext
            work.execute {
                val s = Picture.read(app)
                val m = Xgimi.moves()
                val g = Xgimi.gameMode()
                handler.post {
                    if (pictureChanges > 0) return@post
                    if (s != null) picture = s
                    if (g >= 0) game = g
                    else pictureLine = Ui.tr("Não deu para ler os ajustes de imagem do projetor.", "Couldn't read the projector's picture settings.")
                    if (m != null) moves = m
                    if (screen == PICTURE && focus >= rowCount()) focus = rowCount() - 1
                    invalidate()
                }
            }
        }

        /** The focused row one step on: shown at once, then what the projector took (a mode brings its own HDR and motion). */
        private fun adjustPicture(dir: Int) {
            if (pictureChanges >= 3) return   // a held arrow doesn't queue a long run of changes
            val rows = pictureRows()
            if (focus >= rows.size) return
            val row = rows[focus]
            if (row == P_MOVE_KEYSTONE || row == P_MOVE_FOCUS) {
                toggleMove(row)
                return
            }
            if (row == P_GAME) {
                adjustGame(dir)
                return
            }
            val p = picture ?: return
            val want: Int
            when (row) {
                P_MODE -> {
                    p.mode = nextValue(Picture.MODES, p.mode, dir)
                    want = p.mode
                }
                P_HDR -> {
                    p.hdr = !p.hdr
                    want = if (p.hdr) 1 else 0
                }
                else -> {
                    p.motion = nextValue(Picture.MOTION, p.motion, dir)
                    want = p.motion
                }
            }
            pictureChanges++
            pictureLine = ""
            val app = context.applicationContext
            work.execute {
                val now = if (row == P_MODE) Picture.setMode(app, want) else if (row == P_HDR) Picture.setHdr(app, want == 1)
                    else Picture.setMotion(app, want)
                handler.post {
                    if (--pictureChanges > 0) return@post
                    if (now == null) {
                        pictureLine = Ui.tr("Não deu para ler os ajustes de imagem do projetor.", "Couldn't read the projector's picture settings.")
                    } else {
                        picture = now
                        val got = if (row == P_MODE) now.mode else if (row == P_HDR) (if (now.hdr) 1 else 0) else now.motion
                        if (got != want) pictureLine = Ui.tr("O projetor não aceitou essa mudança.", "The projector didn't take that change.")
                    }
                    if (screen == PICTURE && focus >= rowCount()) focus = rowCount() - 1
                    invalidate()
                }
            }
        }

        /** HDMI game mode one step on: off, on, automatic (on when the projector recognizes a console). */
        private fun adjustGame(dir: Int) {
            game = nextValue(GAME_MODES, game, dir)
            val want = game
            pictureChanges++
            pictureLine = ""
            work.execute {
                val now = Xgimi.setGameMode(want)
                handler.post {
                    if (--pictureChanges > 0) return@post
                    if (now >= 0) game = now
                    pictureLine = if (now != want) Ui.tr("O projetor não aceitou essa mudança.", "The projector didn't take that change.")
                        else if (now == Xgimi.GAME_AUTO) Ui.tr("O modo jogo liga sozinho quando o projetor reconhece um console.",
                            "Game mode turns on by itself when the projector recognizes a console.") else ""
                    invalidate()
                }
            }
        }

        /** Keystone or focus after a move, on or off (turning keystone on squares the picture now too). */
        private fun toggleMove(row: Int) {
            val keystone = row == P_MOVE_KEYSTONE
            val m = moves!!
            val want: Boolean
            if (keystone) {
                m.keystone = !m.keystone
                want = m.keystone
            } else {
                m.focus = !m.focus
                want = m.focus
            }
            pictureChanges++
            pictureLine = if (keystone && want) Ui.tr("Corrigindo a imagem…", "Squaring the picture…") else ""
            val app = context.applicationContext
            work.execute {
                val now = if (keystone) Xgimi.setMoveKeystone(app, want) else Xgimi.setMoveFocus(want)
                handler.post {
                    if (--pictureChanges > 0) return@post
                    if (now != null) moves = now
                    val got = now != null && (if (keystone) now.keystone else now.focus)
                    pictureLine = if (got != want) Ui.tr("O projetor não aceitou essa mudança.", "The projector didn't take that change.") else ""
                    invalidate()
                }
            }
        }

        private fun nextValue(values: IntArray, now: Int, dir: Int): Int {
            val at = Picture.indexOf(values, now)
            return values[if (at < 0) (if (dir > 0) 0 else values.size - 1) else (at + dir + values.size) % values.size]
        }

        // MARK: - Time Zone (XGIMI projectors)

        private fun currentZone(): String {
            return TimeZone.getDefault().id
        }

        /** The main row's value: the zone in use, the one being set, or that it didn't work. */
        private fun zoneSummary(): String {
            if (zoneFailed) return Ui.tr("Não deu para mudar", "Couldn't change it")
            return Zones.label(zoneSetting ?: currentZone())
        }

        /** Every zone, the city's first, the one in use checked. */
        private fun openZones() {
            zoneFailed = false
            val city = HomeData.get(context).weather.zone
            val known = if (Zones.known(city)) city else null
            cityZone = known
            zones.clear()
            if (known != null) zones.add(known)
            zones.addAll(Zones.all())
            openPicker(PICK_ZONE)
        }

        private fun setZone(id: String) {
            if (id == currentZone() || zoneSetting != null) return
            zoneSetting = id
            val app = context.applicationContext
            work.execute {
                val set = try {
                    Zones.set(app, id)
                    true
                } catch (e: IOException) {
                    AppLog.w("time zone: $e")
                    false
                } catch (e: RuntimeException) {
                    AppLog.w("time zone: $e")
                    false
                }
                handler.post {
                    if (set) TimeZone.setDefault(null)   // reads the new one now, before the system's broadcast gets here
                    zoneSetting = null
                    zoneFailed = !set
                    invalidate()
                }
            }
        }

        // MARK: - Glasses (Rokid Station)

        private fun sceneName(scene: Int): String {
            return when (scene) {
                1 -> Ui.tr("Padrão", "Standard")
                2 -> Ui.tr("Filme", "Movie")
                3 -> Ui.tr("Leitura", "Reading")
                4 -> Ui.tr("Descanso visual", "Eye care")
                else -> "—"
            }
        }

        private fun hz(fps: Int): String {
            val unit = if (Ui.ru) " Гц" else " Hz"
            return "$fps$unit"
        }

        /** The main row's value: 3D, or 2D with its refresh rate. */
        private fun glassesSummary(): String {
            val g = glasses
            return if (g == null) "" else if (g.is3d()) "3D" else "2D · " + hz(g.fps)
        }

        private fun glassesLabels(): Array<String> {
            return arrayOf(Ui.tr("Modo", "Mode"), Ui.tr("Taxa de atualização", "Refresh rate"), Ui.tr("Imagem", "Picture"),
                Ui.tr("Alto-falantes", "Speakers"), Ui.tr("Brilho", "Brightness"), Ui.tr("Zoom da tela", "Display zoom"))
        }

        private fun glassesValues(): Array<String> {
            val v = Array(GLASSES_ROWS) { "" }
            val g = glasses
            if (g == null) {
                v.fill("—")
                return v
            }
            v[0] = if (g.is3d()) Ui.tr("3D (lado a lado)", "3D (side by side)") else "2D"
            v[1] = hz(g.fps)
            v[2] = sceneName(g.scene)
            v[3] = if (g.audio == 0) Ui.tr("Privado", "Private") else Ui.tr("Público", "Public")
            val open = Ui.tr("Abrir", "Open")
            v[4] = open
            v[5] = open
            return v
        }

        /** In 3D the glasses run at 60 Hz only: the refresh rate row is locked. */
        private fun glassesLocked(row: Int): Boolean {
            val g = glasses
            return row == 1 && g != null && g.is3d()
        }

        /** Reads the glasses again; the Glasses row comes and goes with them. */
        private fun refreshGlasses() {
            if (glassesChanges > 0) return
            val app = context.applicationContext
            work.execute {
                val s = Rokid.read(app)
                handler.post {
                    if (glassesChanges > 0) return@post
                    val had = glasses != null
                    glasses = s
                    if (had != (s != null)) {
                        val row = if (screen == MAIN) mainRow(focus) else -1
                        mainRows = mainRows(s != null)
                        if (row >= 0) focus = mainIndex(row)
                        if (screen == GLASSES && s == null) {
                            screen = MAIN
                            focus = 0
                        }
                        spoken.focusMoved()
                    }
                    invalidate()
                }
            }
        }

        /** The focused row one step on: shown at once, then what the glasses took (120 Hz reads back as its own mode). */
        private fun adjustGlasses(dir: Int) {
            val g = glasses
            if (g == null || glassesChanges >= 3) return   // a held arrow doesn't queue a long run of changes
            val row = focus
            if (row >= SLIDER_ROW) return   // Brightness and Display zoom only open, with OK
            if (glassesLocked(row)) {
                glassesLine = Ui.tr("120 Hz é só no 2D.", "120 Hz is for 2D only.")
                return
            }
            val want: Int
            when (row) {
                0 -> {
                    g.mode = if (g.is3d()) Rokid.MODE_2D else Rokid.MODE_3D
                    want = g.mode
                }
                1 -> {
                    g.fps = nextValue(Rokid.FPS, g.fps, dir)
                    want = g.fps
                }
                2 -> {
                    g.scene = nextValue(Rokid.SCENES, g.scene, dir)
                    want = g.scene
                }
                else -> {
                    g.audio = nextValue(Rokid.AUDIO, g.audio, dir)
                    want = g.audio
                }
            }
            glassesChanges++
            glassesLine = ""
            val app = context.applicationContext
            work.execute {
                val now = if (row == 0) Rokid.setMode(app, want) else if (row == 1) Rokid.setFps(app, want)
                    else if (row == 2) Rokid.setScene(app, want) else Rokid.setAudio(app, want)
                handler.post {
                    if (--glassesChanges > 0) return@post
                    if (now == null) {
                        glassesLine = Ui.tr("Não deu para falar com os óculos.", "Couldn't reach the glasses.")
                        refreshGlasses()
                    } else {
                        glasses = now
                        val got = if (row == 0) (if (now.is3d()) Rokid.MODE_3D else Rokid.MODE_2D) else if (row == 1) now.fps
                            else if (row == 2) now.scene else now.audio
                        if (got != want) glassesLine = Ui.tr("Os óculos não aceitaram essa mudança.", "The glasses didn't take that change.")
                    }
                    invalidate()
                }
            }
        }

        // MARK: - Power (Rokid Station)

        /** The main row's value: the battery, and whether it's charging. */
        private fun powerSummary(): String {
            val b = battery ?: return ""
            val charging = if (b.plugged) " · " + Ui.tr("Carregando", "Charging") else ""
            return "${b.level}%$charging"
        }

        private fun powerLabels(): Array<String> {
            return arrayOf(Ui.tr("Bateria", "Battery"), Ui.tr("Tela de descanso após", "Screensaver after"),
                Ui.tr("Dormir após", "Sleep after"), Ui.tr("Timer para dormir", "Sleep timer"))
        }

        private fun powerValues(): Array<String> {
            val v = Array(4) { "" }
            val b = battery
            v[0] = if (b == null) "—" else "${b.level}% · " + (if (b.plugged) Ui.tr("Carregando", "Charging") else Ui.tr("Na bateria", "On battery"))
            val t = timeouts
            v[1] = if (t == null) "—" else Power.seconds(t.screensaver)
            v[2] = if (t == null) "—" else if (t.sleep < 0) Ui.tr("Nunca", "Never") else Power.seconds(t.sleep)
            val left = Power.left(timerEnd)
            v[3] = if (left == 0) Ui.tr("Desligado", "Off") else Power.duration(left)
            return v
        }

        /** The battery and the timer at once; the timeouts from the TV's ADB. */
        private fun refreshPower() {
            if (powerChanges > 0) return
            val app = context.applicationContext
            battery = Power.battery(app)
            timerEnd = Power.timerEnd(app)
            work.execute {
                val read = try {
                    Power.timeouts(app)
                } catch (e: IOException) {
                    AppLog.w("power: " + e.message)
                    null
                }
                handler.post {
                    if (powerChanges > 0) return@post
                    if (read != null) timeouts = read
                    else powerLine = Ui.tr("Não deu para ler os ajustes de energia.", "Couldn't read the power settings.")
                    invalidate()
                }
            }
        }

        /** The focused row one step on: the battery is only shown; the timer is ours; the timeouts are read back. */
        private fun adjustPower(dir: Int) {
            val row = focus
            if (row == 0) return
            if (row == 3) {
                Power.setTimer(context, Power.nextTimer(Power.left(timerEnd), dir))
                timerEnd = Power.timerEnd(context)
                powerLine = ""
                return
            }
            val t = timeouts
            if (t == null || powerChanges >= 3) return   // a held arrow doesn't queue a long run of changes
            val want: Int
            if (row == 1) {
                t.screensaver = nextValue(Power.SCREENSAVER, t.screensaver, dir)
                want = t.screensaver
            } else {
                t.sleep = nextValue(Power.SLEEP, t.sleep, dir)
                want = t.sleep
            }
            powerChanges++
            powerLine = ""
            val app = context.applicationContext
            work.execute {
                val got = try {
                    if (row == 1) Power.setScreensaver(app, want) else Power.setSleep(app, want)
                } catch (e: IOException) {
                    AppLog.w("power: " + e.message)
                    null
                }
                handler.post {
                    if (--powerChanges > 0) return@post
                    if (got == null) {
                        powerLine = Ui.tr("Não deu para mudar os ajustes de energia.", "Couldn't change the power settings.")
                    } else {
                        timeouts = got
                        if ((if (row == 1) got.screensaver else got.sleep) != want) {
                            powerLine = Ui.tr("A Station não aceitou essa mudança.", "The Station didn't take that change.")
                        }
                    }
                    invalidate()
                }
            }
        }

        // MARK: - Wi-Fi (XGIMI projectors, Rokid Station)

        private fun wifiCount(): Int {
            val s = wifi
            return if (s == null) 0 else s.networks.size
        }

        /** The network at list position `at` (0 is the Wi-Fi on/off row). */
        private fun wifiNet(at: Int): Wifi.Network? {
            val s = wifi
            return if (s != null && at >= 1 && at <= s.networks.size) s.networks[at - 1] else null
        }

        private fun wifiOnOff(): String {
            val s = wifi
            return if (s == null) "…" else onOff(s.enabled)
        }

        /** The main row's value: the network in use. */
        private fun wifiSummary(): String {
            val s = wifi ?: return ""
            if (!s.enabled) return onOff(false)
            return if (s.connected.isEmpty()) Ui.tr("Não conectado", "Not connected") else s.connected
        }

        private fun netValue(n: Wifi.Network): String {
            if (n.connected) return Ui.tr("Conectado", "Connected")
            if (n.saved()) return Ui.tr("Salva", "Saved")
            if (n.security == "open" || n.security == "owe") return Ui.tr("Aberta", "Open")
            return ""
        }

        private fun forgetRows(): Array<Array<String>> {
            val picked = wifiPicked
            val inUse = picked != null && picked.connected
            return arrayOf(
                arrayOf(Ui.tr("Esquecer esta rede", "Forget this network"), if (!inUse) Ui.tr("Ela não entra mais sozinha.", "It won't join by itself anymore.")
                    else if (HomeMode.ROKID) Ui.tr("A Station se desconecta dela agora.", "The Station disconnects from it now.")
                    else Ui.tr("O projetor se desconecta dela agora.", "The projector disconnects from it now.")),
                arrayOf(Ui.tr("Cancelar", "Cancel"), Ui.tr("Manter salva", "Keep it saved")))
        }

        private fun openWifi() {
            screen = WIFI
            focus = 0
            pickScroll = 0
            wifiLine = ""
            handler.removeCallbacks(wifiScan)
            handler.post(wifiScan)
        }

        /** Reads the Wi-Fi in the background; with `scan`, shows what's known at once, scans, and reads again. */
        private fun refreshWifi(scan: Boolean) {
            val c = context
            work.execute {
                try {
                    if (scan) {
                        showWifi(Wifi.read(c))
                        Wifi.scan(c)
                        SystemClock.sleep(4000)
                    }
                    showWifi(Wifi.read(c))
                } catch (e: IOException) {
                    AppLog.w("wifi: " + e.message)
                    handler.post {
                        wifiLine = Ui.tr("Não deu para ler o Wi-Fi: ", "Couldn't read the Wi-Fi: ") + e.message
                        invalidate()
                    }
                }
            }
        }

        private fun showWifi(s: Wifi.Status) {
            handler.post {
                val was = if (screen == WIFI) wifiNet(pickScroll + focus) else null
                wifi = s
                if (screen == WIFI) keepWifiFocus(was?.ssid)
                invalidate()
            }
        }

        /** After the list changes (the networks move with their signal), the focus stays on the same network. */
        private fun keepWifiFocus(ssid: String?) {
            val total = 1 + wifiCount()
            val visible = min(PICK_VISIBLE, total)
            var at = min(pickScroll + focus, total - 1)
            val s = wifi
            if (ssid != null && s != null) for (i in 0 until s.networks.size) if (s.networks[i].ssid == ssid) at = i + 1
            if (at < pickScroll) pickScroll = at
            else if (at >= pickScroll + visible) pickScroll = at - visible + 1
            pickScroll = max(0, min(pickScroll, total - visible))
            focus = at - pickScroll
        }

        /** OK on the list: Wi-Fi on or off, forget a saved network, or join a new one (asking for its password). */
        private fun activateWifi() {
            if (wifiBusy) return
            val at = pickScroll + focus
            if (at == 0) {
                val s = wifi ?: return
                val on = !s.enabled
                val c = context
                wifiBusy = true
                work.execute {
                    try {
                        Wifi.setEnabled(c, on)
                    } catch (e: IOException) {
                        AppLog.w("wifi on/off: " + e.message)
                    }
                    handler.post { wifiBusy = false }
                }
                refreshWifi(true)
                return
            }
            val n = wifiNet(at) ?: return
            if (n.connected || n.saved()) {
                wifiPicked = n
                screen = WIFI_FORGET
                focus = 1   // Cancel: forgetting is never one press away
            } else if (n.security.isEmpty()) {
                wifiLine = Ui.tr("Esta rede pede um login que o " + Config.APP_NAME + " não faz: use os Ajustes da TV.",
                    "This network needs a sign-in " + Config.APP_NAME + " can't do: use TV Settings.")
            } else if (n.needsPassword()) {
                askPassword(n)
            } else {
                joinWifi(n, "")
            }
        }

        private fun askPassword(n: Wifi.Network) {
            val field = EditText(activity)
            field.setSingleLine(true)
            // A visible password: the TV's keyboard types plain letters, and the person sees what they typed.
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            field.hint = Ui.tr("Senha do Wi-Fi", "Wi-Fi password")
            field.imeOptions = EditorInfo.IME_ACTION_DONE
            val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(n.ssid)
                .setView(field)
                .setPositiveButton(Ui.tr("Conectar", "Connect")) { _, _ -> typedPassword(n, field.text.toString()) }
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create()
            field.setOnEditorActionListener { _, _, _ ->
                dialog.dismiss()
                typedPassword(n, field.text.toString())
                true
            }
            dialog.show()
        }

        private fun typedPassword(n: Wifi.Network, password: String) {
            if (!Wifi.validPassword(password)) {
                wifiLine = Ui.tr("A senha tem de 8 a 63 letras, números ou símbolos.", "The password has 8 to 63 letters, numbers or symbols.")
                invalidate()
                return
            }
            joinWifi(n, password)
        }

        private fun joinWifi(n: Wifi.Network, password: String) {
            val c = context
            wifiBusy = true
            wifiLine = Ui.tr("Conectando a ", "Connecting to ") + n.ssid + "…"
            invalidate()
            work.execute {
                val line = try {
                    if (Wifi.connect(c, n, password)) Ui.tr("Conectado a ", "Connected to ") + n.ssid + "."
                    else Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + n.ssid +
                        (if (n.needsPassword()) Ui.tr(". Confira a senha.", ". Check the password.") else ".")
                } catch (e: IOException) {
                    Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + n.ssid + ": " + e.message
                }
                handler.post {
                    wifiBusy = false
                    wifiLine = line
                    invalidate()
                }
                try {
                    showWifi(Wifi.read(c))
                } catch (e: IOException) {
                    AppLog.w("wifi: " + e.message)
                }
            }
        }

        private fun forgetWifi(n: Wifi.Network) {
            val c = context
            work.execute {
                try {
                    Wifi.forget(c, n)
                    handler.post {
                        wifiLine = Ui.tr("Esqueci ", "Forgot ") + n.ssid + "."
                        invalidate()
                    }
                    showWifi(Wifi.read(c))
                } catch (e: IOException) {
                    AppLog.w("wifi forget: " + e.message)
                }
            }
        }

        /** Networks nearby, the one in use first: signal bars, a lock on the ones with a password. */
        private fun drawWifi(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(Ui.tr("Wi-Fi", "Wi-Fi"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = rowCount()
            val ctx = context
            for (i in 0 until rows) {
                val at = pickScroll + i
                val y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k)
                val f = drawRowBackground(c, i)
                val ink = blend(0xF2FFFFFF.toInt(), INK, f)
                rowLabel.color = ink
                rowValue.color = blend(0xB3FFFFFF.toInt(), 0xB31C1C1E.toInt(), f)
                val base = rowRect.centerY() + Ui.halfCap(rowLabel)
                val left = rowRect.left + 30 * k
                var right = rowRect.right - 30 * k
                val n = wifiNet(at)
                if (n == null) {
                    c.drawText(Ui.tr("Wi-Fi", "Wi-Fi"), left, base, rowLabel)
                    c.drawText(wifiOnOff(), right, base, rowValue)
                    continue
                }
                val bw = Symbols.width(ctx, "wifi_4", 28 * k)
                Symbols.draw(ctx, c, WIFI_BARS[n.bars()], right - bw / 2, rowRect.centerY(), 28 * k, ink)
                right -= bw + 16 * k
                if (n.needsPassword()) {
                    val lw = Symbols.width(ctx, "lock", 22 * k)
                    Symbols.draw(ctx, c, "lock", right - lw / 2, rowRect.centerY(), 22 * k, blend(0x8CFFFFFF.toInt(), 0x8C1C1C1E.toInt(), f))
                    right -= lw + 16 * k
                }
                val value = netValue(n)
                if (!value.isEmpty()) {
                    c.drawText(value, right, base, rowValue)
                    right -= rowValue.measureText(value) + 24 * k
                }
                c.drawText(TextUtils.ellipsize(n.ssid, TextPaint(rowLabel), right - left, TextUtils.TruncateAt.END).toString(), left, base, rowLabel)
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            val hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k
            val s = wifi
            val line = if (!wifiLine.isEmpty()) wifiLine else if (s == null) Ui.tr("Procurando redes…", "Searching for networks…")
                else if (!s.enabled) Ui.tr("O Wi-Fi está desligado.", "Wi-Fi is off.")
                else if (s.networks.isEmpty()) Ui.tr("Procurando redes…", "Searching for networks…")
                else Ui.tr("OK conecta. As redes salvas entram sozinhas.", "OK connects. Saved networks join by themselves.")
            c.drawText(TextUtils.ellipsize(line, TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                LIST_X * k + 30 * k, hy, hint)
        }

        /** A saved network: forget it, or keep it (the focus starts on keeping it). */
        private fun drawForget(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(TextUtils.ellipsize(wifiPicked!!.ssid, TextPaint(heading), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = forgetRows()
            for (i in rows.indices) {
                val y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(0xF2FFFFFF.toInt(), INK, f)
                rowSub.color = blend(0x8CFFFFFF.toInt(), 0x991C1C1E.toInt(), f)
                c.drawText(rows[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel)
                c.drawText(rows[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub)
            }
        }

        // MARK: - Bluetooth (XGIMI projectors, Rokid Station)

        private fun btCount(): Int {
            return btPaired.size + btFound.size
        }

        /** The device at list position `at`: 0 is "Pair a new device", then the paired ones, then what the search found. */
        private fun btItem(at: Int): Bluetooth.Device? {
            if (at < 1) return null
            var i = at - 1
            if (i < btPaired.size) return btPaired[i]
            i -= btPaired.size
            return if (i < btFound.size) btFound[i] else null
        }

        /** The main row's value: the speaker or headphones in use. */
        private fun btSummary(): String {
            for (d in btPaired) if (d.connected && d.audio) return d.name
            return ""
        }

        private fun btValue(d: Bluetooth.Device): String {
            if (!d.bonded) return if (d === btPairing) Ui.tr("Pareando…", "Pairing…") else Ui.tr("Parear", "Pair")
            if (d.peripheral) return Ui.tr("Controle", "Remote")
            return if (d.connected) Ui.tr("Conectado", "Connected") else ""
        }

        /** What OK offers on a paired device: connect or disconnect (speakers, headphones), then forget it. */
        private fun btActions(): Array<Array<String>> {
            val rows = ArrayList<Array<String>>()
            val picked = btPicked
            if (picked != null && picked.audio) {
                if (HomeMode.ROKID) {
                    rows.add(if (picked.connected)
                        arrayOf(Ui.tr("Desconectar", "Disconnect"), Ui.tr("O som volta para os óculos.", "The sound goes back to the glasses."))
                        else arrayOf(Ui.tr("Conectar", "Connect"), Ui.tr("O som da Station sai por ele.", "Play the Station's sound on it.")))
                } else {
                    rows.add(if (picked.connected)
                        arrayOf(Ui.tr("Desconectar", "Disconnect"), Ui.tr("O som volta para o projetor.", "The sound goes back to the projector."))
                        else arrayOf(Ui.tr("Conectar", "Connect"), Ui.tr("O som do projetor sai por ele.", "Play the projector's sound on it.")))
                }
            }
            rows.add(arrayOf(Ui.tr("Esquecer este aparelho", "Forget this device"), Ui.tr("Para usar, pareie de novo.", "Pair it again to use it.")))
            return rows.toTypedArray()
        }

        /** Listens to the search, pairing and connections, and opens the audio profile (to connect speakers). */
        private fun openBt() {
            // A pairing that ended while the screen was away (its broadcast missed) doesn't block the next one.
            val pairing = btPairing
            if (pairing != null && pairing.device.bondState != BluetoothDevice.BOND_BONDING) btPairing = null
            if (!btListening) {
                val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                Ui.register(context, btEvents, f)
                btListening = true
                Bluetooth.openAudio(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (btListening) a2dp = proxy else Bluetooth.closeAudio(proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        a2dp = null
                    }
                })
            }
            refreshBt()
        }

        private fun closeBt() {
            if (!btListening) return
            btListening = false
            context.unregisterReceiver(btEvents)
            Bluetooth.stopSearch()
            btSearching = false
            Bluetooth.closeAudio(a2dp)
            a2dp = null
        }

        private fun refreshBt() {
            work.execute {
                val paired = Bluetooth.paired()
                handler.post {
                    val was = if (screen == BT) btItem(pickScroll + focus) else null
                    btPaired = paired
                    for (p in paired) {
                        for (i in btFound.size - 1 downTo 0) if (btFound[i].address == p.address) btFound.removeAt(i)
                    }
                    if (screen == BT) keepBtFocus(was?.address)
                    invalidate()
                }
            }
        }

        /** A device the search found: kept once (its strongest signal), speakers and headphones first. */
        private fun addFound(dev: Bluetooth.Device) {
            val was = if (screen == BT) btItem(pickScroll + focus) else null
            for (i in btFound.size - 1 downTo 0) {
                val old = btFound[i]
                if (old.address != dev.address) continue
                if (old.rssi > dev.rssi) dev.rssi = old.rssi
                btFound.removeAt(i)
            }
            btFound.add(dev)
            btFound.sortWith(Comparator { x, y -> if (x.audio != y.audio) (if (x.audio) -1 else 1) else y.rssi - x.rssi })
            if (screen == BT) keepBtFocus(was?.address)
        }

        /** After the list changes, the focus stays on the same device. */
        private fun keepBtFocus(address: String?) {
            val total = 1 + btCount()
            val visible = min(PICK_VISIBLE, total)
            var at = min(pickScroll + focus, total - 1)
            if (address != null) for (i in 1 until total) if (btItem(i)!!.address == address) at = i
            if (at < pickScroll) pickScroll = at
            else if (at >= pickScroll + visible) pickScroll = at - visible + 1
            pickScroll = max(0, min(pickScroll, total - visible))
            focus = at - pickScroll
        }

        /** OK on the list: search, pair a found device, or open a paired one's choices (never the remote's). */
        private fun activateBt() {
            val at = pickScroll + focus
            if (at == 0) {
                if (Bluetooth.search()) {
                    btSearching = true
                    btLine = ""
                } else {
                    btLine = Ui.tr("Não deu para procurar aparelhos agora.", "Couldn't search for devices right now.")
                }
                return
            }
            val d = btItem(at) ?: return
            if (!d.bonded) {
                if (btPairing != null) return
                if (Bluetooth.pair(d)) {
                    btPairing = d
                    btLine = Ui.tr("Pareando com ", "Pairing with ") + d.name + "…"
                } else {
                    btLine = Ui.tr("Não deu para parear com ", "Couldn't pair with ") + d.name +
                        Ui.tr(". Coloque-o no modo de pareamento e tente de novo.", ". Put it in pairing mode and try again.")
                }
            } else if (d.peripheral) {
                btLine = Ui.tr("O controle fica como está: pareie ou esqueça nos Ajustes da TV.",
                    "The remote stays as it is: pair or forget it in TV Settings.")
            } else {
                btPicked = d
                screen = BT_DEVICE
                focus = 0
            }
        }

        private fun activateBtDevice() {
            val d = btPicked!!
            val connectRow = d.audio && focus == 0
            screen = BT
            keepBtFocus(d.address)
            if (!connectRow) {
                btLine = if (Bluetooth.forget(d)) Ui.tr("Esqueci ", "Forgot ") + d.name + "."
                    else Ui.tr("Não deu para esquecer ", "Couldn't forget ") + d.name + "."
                refreshBt()
                return
            }
            val on = !d.connected
            if (!Bluetooth.connect(a2dp, d, on)) {
                btLine = Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + d.name + "."
                return
            }
            if (!on) return
            val connecting = Ui.tr("Conectando a ", "Connecting to ") + d.name + "…"
            btLine = connecting
            // It takes a few seconds; the connection broadcasts refresh the list, and this says how it went.
            handler.postDelayed({
                if (connecting != btLine) return@postDelayed
                var ok = false
                for (p in btPaired) ok = ok or (p.address == d.address && p.connected)
                btLine = if (ok) Ui.tr("Conectado a ", "Connected to ") + d.name + "."
                    else Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + d.name +
                        Ui.tr(". Ligue o aparelho e tente de novo.", ". Turn it on and try again.")
                invalidate()
            }, 10_000)
        }

        /** Pair a new device, the paired ones (the remote last), then the ones the search found. */
        private fun drawBt(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText("Bluetooth", LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = rowCount()
            for (i in 0 until rows) {
                val at = pickScroll + i
                val y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k)
                val f = drawRowBackground(c, i)
                val d = btItem(at)
                val dim = d != null && d.peripheral
                rowLabel.color = blend(if (dim) 0x80FFFFFF.toInt() else 0xF2FFFFFF.toInt(), if (dim) 0x801C1C1E.toInt() else INK, f)
                rowValue.color = blend(0xB3FFFFFF.toInt(), 0xB31C1C1E.toInt(), f)
                val base = rowRect.centerY() + Ui.halfCap(rowLabel)
                val left = rowRect.left + 30 * k
                var right = rowRect.right - 30 * k
                val label = if (d == null) Ui.tr("Parear um aparelho novo", "Pair a new device") else d.name
                val value = if (d == null) (if (btSearching) Ui.tr("Procurando…", "Searching…") else "") else btValue(d)
                if (!value.isEmpty()) {
                    c.drawText(value, right, base, rowValue)
                    right -= rowValue.measureText(value) + 24 * k
                }
                c.drawText(TextUtils.ellipsize(label, TextPaint(rowLabel), right - left, TextUtils.TruncateAt.END).toString(), left, base, rowLabel)
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            val hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k
            val line = if (!btLine.isEmpty()) btLine else if (btSearching)
                Ui.tr("Coloque o aparelho no modo de pareamento e escolha abaixo.", "Put the device in pairing mode, then pick it below.")
                else Ui.tr("OK pareia, conecta ou esquece. O controle fica como está.", "OK pairs, connects or forgets. The remote stays as it is.")
            c.drawText(TextUtils.ellipsize(line, TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                LIST_X * k + 30 * k, hy, hint)
        }

        /** A paired speaker or headphones: connect or disconnect, or forget it. */
        private fun drawBtDevice(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(TextUtils.ellipsize(btPicked!!.name, TextPaint(heading), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = btActions()
            for (i in rows.indices) {
                val y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(0xF2FFFFFF.toInt(), INK, f)
                rowSub.color = blend(0x8CFFFFFF.toInt(), 0x991C1C1E.toInt(), f)
                c.drawText(rows[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel)
                c.drawText(rows[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub)
            }
        }

        // MARK: - Shelf editor

        private fun openShelfEditor() {
            shelfOrder.clear()
            // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the limit)
            // without a row to take it off.
            val pm = context.packageManager
            for (pkg in HomeApps.shelfPackages(context)) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg)
            rebuildShelfRows(HomeApps.shelfCandidates(context))
            pickScroll = 0
            focus = 0
            screen = SHELF_EDIT
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private fun rebuildShelfRows(candidates: List<Array<String>>) {
            shelfRows.clear()
            for (pkg in shelfOrder) {
                for (cand in candidates) if (cand[0] == pkg) {
                    shelfRows.add(cand)
                    break
                }
            }
            for (cand in candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand)
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private fun refreshShelfRows(focusedPkg: String) {
            val all = ArrayList(shelfRows)
            rebuildShelfRows(all)
            for (i in shelfRows.indices) {
                if (shelfRows[i][0] != focusedPkg) continue
                val visible = rowCount()
                if (i < pickScroll) pickScroll = i
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1
                focus = i - pickScroll
                break
            }
            Prefs.setShelf(shelfOrder)
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private fun toggleShelfApp() {
            val pkg = shelfRows[pickScroll + focus][0]
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg)
            else if (shelfOrder.size < HomeApps.MAX_SHELF) shelfOrder.add(pkg)
            else return
            refreshShelfRows(pkg)
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private fun moveShelfApp(dir: Int) {
            val pkg = shelfRows[pickScroll + focus][0]
            val at = shelfOrder.indexOf(pkg)
            val to = at + dir
            if (at < 0 || to < 0 || to >= shelfOrder.size) return
            shelfOrder.removeAt(at)
            shelfOrder.add(to, pkg)
            refreshShelfRows(pkg)
        }

        private fun drawShelfEditor(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = rowCount()
            for (i in 0 until rows) {
                val app = shelfRows[pickScroll + i]
                val place = shelfOrder.indexOf(app[0])
                val y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(if (place >= 0) 0xF2FFFFFF.toInt() else 0x80FFFFFF.toInt(), if (place >= 0) INK else 0x801C1C1E.toInt(), f)
                rowValue.color = blend(0xB3FFFFFF.toInt(), 0xB31C1C1E.toInt(), f)
                val base = rowRect.centerY() + Ui.halfCap(rowLabel)
                c.drawText(TextUtils.ellipsize(app[1], TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel)
                c.drawText(if (place >= 0) "#${place + 1}" else Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue)
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            val hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint)
        }

        private fun openPicker(which: Int) {
            pickFor = which
            pickLabels.clear()
            var start = -1   // where the focus starts (the one in use, if not given)
            if (which == PICK_CITY) {
                for (city in cities) pickLabels.add(city[0])
                pickCurrent = -1
            } else if (which == PICK_ZONE) {
                val now = currentZone()
                for (id in zones) pickLabels.add(Zones.label(id))
                val zone = cityZone
                if (zone != null) pickLabels[0] = pickLabels[0] + "  ·  " + Ui.tr("sua cidade", "your city")
                pickCurrent = zones.indexOf(now)
                if (zone != null && zone != now) start = 0   // the city's zone, first, is what they likely want
            }
            val visible = min(PICK_VISIBLE, pickLabels.size)
            val at = if (start >= 0) start else max(0, pickCurrent)
            pickScroll = max(0, min(at - 3, pickLabels.size - visible))
            focus = at - pickScroll
            screen = PICKER
        }

        /** A long list (cities, time zones): nine rows at a time, the one in use checked. */
        private fun drawPicker(c: Canvas) {
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            val title = if (pickFor == PICK_CITY) Ui.tr("Cidade", "City") else Ui.tr("Fuso Horário", "Time Zone")
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            val rows = rowCount()
            for (i in 0 until rows) {
                val at = pickScroll + i
                val y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(0xF2FFFFFF.toInt(), INK, f)
                c.drawText(pickLabels[at], rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel)
                if (at == pickCurrent) {
                    val cw = Symbols.width(context, "check", 26 * k)
                    Symbols.draw(context, c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF.toInt(), INK, f))
                }
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            val hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k
            c.drawText("${pickScroll + focus + 1} / ${pickLabels.size}  ·  ${Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels.")}",
                LIST_X * k + 30 * k, hy, hint)
        }

        private fun drawList(c: Canvas) {
            val pic = screen == PICTURE
            val eyes = screen == GLASSES
            val power = screen == POWER
            val labels = listLabels()
            val values = listValues()
            val heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF.toInt())
            c.drawText(if (pic) Ui.tr("Imagem", "Picture") else if (eyes) Ui.tr("Óculos", "Glasses")
                else if (power) Ui.tr("Energia", "Power") else Ui.tr("Ajustes do Início", "Home Settings"),
                LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading)
            for (i in labels.indices) {
                val y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k)
                val f = drawRowBackground(c, i)
                rowLabel.color = blend(0xF2FFFFFF.toInt(), INK, f)
                val locked = screen == HOME_SETTINGS && i >= COIN_ROWS && !Prefs.quotes() || eyes && glassesLocked(i)
                rowValue.color = blend(if (locked) 0x59FFFFFF else 0xB3FFFFFF.toInt(), if (locked) 0x591C1C1E else 0xB31C1C1E.toInt(), f)
                if (locked) rowLabel.color = blend(0x73FFFFFF, 0x731C1C1E, f)
                val base = rowRect.centerY() + Ui.halfCap(rowLabel)
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel)
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue)
            }
            val hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF.toInt())
            val hy = (LIST_TOP + 20 + labels.size * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint)
            if (pic || eyes || power) {
                val t = timeouts
                val line = if (pic) (if (!pictureLine.isEmpty()) pictureLine else Ui.tr("As mudanças aparecem na hora.", "Changes show at once."))
                    else if (eyes) (if (!glassesLine.isEmpty()) glassesLine else Ui.tr("O Início volta os óculos para o 2D.", "Home puts the glasses back in 2D."))
                    else if (!powerLine.isEmpty()) powerLine else if (t != null && t.stayOnPlugged)
                        Ui.tr("Na tomada, a tela de descanso não começa.", "While plugged in, the screensaver doesn't start.") else ""
                c.drawText(TextUtils.ellipsize(line, TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, hy + 38 * k, hint)
            }
        }

        private fun activate() {
            val c = context
            if (screen == WIFI) {
                activateWifi()
                invalidate()
                spoken.focusMoved()
                return
            }
            if (screen == WIFI_FORGET) {
                if (focus == 0) forgetWifi(wifiPicked!!)
                screen = WIFI
                keepWifiFocus(wifiPicked!!.ssid)
                invalidate()
                spoken.focusMoved()
                return
            }
            if (screen == BT || screen == BT_DEVICE) {
                if (screen == BT) activateBt() else activateBtDevice()
                invalidate()
                spoken.focusMoved()
                return
            }
            if (screen == PICTURE) {
                adjustPicture(1)
                invalidate()
                return
            }
            if (screen == POWER) {
                adjustPower(1)
                invalidate()
                return
            }
            if (screen == GLASSES && focus >= SLIDER_ROW) {
                if (!Rokid.openSlider(c, if (focus == SLIDER_ROW) Rokid.BRIGHTNESS else Rokid.ZOOM)) {
                    glassesLine = Ui.tr("Não deu para abrir.", "Couldn't open it.")
                }
                invalidate()
                return
            }
            if (screen == GLASSES) {
                adjustGlasses(1)
                invalidate()
                return
            }
            if (screen == HOME_CHOICE) {
                val wantFree = focus == 0
                val already = if (wantFree) freeActive() else HomeMode.ORIGINAL == state.optString("mode", HomeMode.ORIGINAL)
                if (!already && !busy()) {
                    HomeMode.request(c, if (wantFree) HomeMode.FREE else HomeMode.ORIGINAL)
                    pendingAt = SystemClock.uptimeMillis()
                }
                screen = MAIN
                focus = 0
            } else if (screen == PICKER && pickFor == PICK_ZONE) {
                setZone(zones[pickScroll + focus])
                focus = mainIndex(ZONE_ROW)
                screen = MAIN
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                val city = cities[pickScroll + focus]
                Prefs.setCity(city[1], city[2], city[3])
                HomeData.get(c).settingsChanged()
                focus = CITY_ROW
                screen = HOME_SETTINGS
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp()
                invalidate()
                return
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity()
                return
            } else if (screen == HOME_SETTINGS) {
                adjust(1)
                invalidate()
                return
            } else if (mainRow(focus) == 0) {
                if (busy()) return
                screen = HOME_CHOICE
                focus = if (freeActive()) 0 else 1
            } else if (mainRow(focus) == 1) {
                pause()
                activity.showPreview()
            } else if (mainRow(focus) == 2) {
                screen = HOME_SETTINGS
                focus = 0
            } else if (mainRow(focus) == 3) {
                openShelfEditor()
            } else if (mainRow(focus) == WIFI_ROW) {
                openWifi()
            } else if (mainRow(focus) == BT_ROW) {
                screen = BT
                focus = 0
                pickScroll = 0
                btLine = ""
                btFound.clear()
                openBt()
            } else if (mainRow(focus) == ZONE_ROW) {
                openZones()
            } else if (mainRow(focus) == PICTURE_ROW) {
                screen = PICTURE
                focus = 0
                pictureLine = ""
                refreshPicture()
            } else if (mainRow(focus) == POWER_ROW) {
                screen = POWER
                focus = 0
                powerLine = ""
                refreshPower()
            } else if (mainRow(focus) == GLASSES_ROW) {
                screen = GLASSES
                focus = 0
                glassesLine = ""
                refreshGlasses()
            } else {
                HomeApps.openTvSettings(c)
            }
            for (s in pop) s.snap(0f)
            invalidate()
            spoken.focusMoved()
        }

        private companion object {
            const val MAIN = 0
            const val HOME_CHOICE = 1
            const val HOME_SETTINGS = 2
            const val PICKER = 3
            const val SHELF_EDIT = 4
            const val WIFI = 5
            const val WIFI_FORGET = 6
            const val BT = 7
            const val BT_DEVICE = 8
            const val PICTURE = 9
            const val GLASSES = 10
            const val POWER = 11
            /**
             * The main screen's rows (TV Settings, Wi-Fi, Bluetooth, Picture, Time Zone, Glasses, Power), shown in the
             * order of mainRows().
             */
            const val TV_ROW = 4
            const val WIFI_ROW = 5
            const val BT_ROW = 6
            const val PICTURE_ROW = 7
            const val ZONE_ROW = 8
            const val GLASSES_ROW = 9
            const val POWER_ROW = 10
            val WIFI_BARS = arrayOf("wifi_1", "wifi_1", "wifi_2", "wifi_3", "wifi_4")
            const val LIST_ROW_H = 68f
            const val LIST_ROW_GAP = 6f
            const val PICK_VISIBLE = 9
            const val LIST_X = 1000f
            const val LIST_R = 1824f
            const val LIST_TOP = 186f
            const val ROW_H = 74f
            const val ROW_GAP = 8f
            const val CHOICE_H = 104f
            const val AMBER = 0xFFFFC266.toInt()
            const val INK = 0xFF1C1C1E.toInt()
            const val PICK_CITY = 0
            const val PICK_ZONE = -1

            // Home Settings
            val LANGUAGES = arrayOf("auto", "pt", "en", "es", "de", "ru")
            val UNITS = arrayOf("auto", "C", "F")
            val THEMES = arrayOf("auto", "light", "dark")
            /** Home Settings: language, temperature, theme, sounds, weather, city, quotes, then one row per coin. */
            const val CITY_ROW = 5
            const val COIN_ROWS = 7

            // Picture
            /**
             * The Picture screen's rows: picture mode, HDR, motion smoothing in the modes that have it (as in XGIMI's
             * menu), HDMI game mode, then what a move does.
             */
            const val P_MODE = 0
            const val P_HDR = 1
            const val P_MOTION = 2
            const val P_MOVE_KEYSTONE = 3
            const val P_MOVE_FOCUS = 4
            const val P_GAME = 5
            val GAME_MODES = intArrayOf(Xgimi.GAME_OFF, Xgimi.GAME_ON, Xgimi.GAME_AUTO)

            // Glasses
            /** The Glasses page: four settings, then Brightness and Display zoom, which open Rokid's own sliders. */
            const val GLASSES_ROWS = 6
            const val SLIDER_ROW = 4
        }
    }
}
