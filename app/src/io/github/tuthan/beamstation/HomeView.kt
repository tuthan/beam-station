package io.github.tuthan.beamstation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import java.io.IOException
import java.util.ArrayDeque
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
internal class HomeView(a: Activity) : ViewGroup(a), Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private companion object {
        // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
        // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
        // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
        const val MARGIN = 80f
        const val CONTENT_R = 1840f
        // Hero, anchored at the bottom (a two-line title grows upward).
        const val HERO_TITLE_W = 900f
        // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
        // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
        // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
        // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
        const val SHELF_PAD = 40f
        const val TILE_GAP = 48f
        const val TILE_RADIUS = 22f
        // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
        const val FOCUS_GROW = 0.2f
        // Corner capsule.
        // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
        const val CAPSULE_TOP = 46f
        const val CAPSULE_H = 68f
        const val BUTTON = 56f
        // Bottom drawer (measured with it open; everything moves up by SHIFT).
        const val SHIFT = 560f
        const val ROW_TITLE = 518f
        const val ROW_TOP = 540f
        const val ROW_H = 126f
        const val CARDS_TOP = 708f
        const val CARDS_H = 300f
        // "All Apps" sheet.
        const val COLUMNS = 5
        const val VISIBLE_ROWS = 3
        const val GRID_W = 300f
        const val GRID_H = 169f
        const val GRID_GAP = 40f
        const val GRID_ROW = 239f
        const val GRID_TOP = 196f
        const val GRID_RADIUS = 22f
        const val SMOKE = 0xFF111113.toInt()
        const val INK = 0xFF1C1C1E.toInt()
        const val WHITE = 0xFFF5F5F7.toInt()
        const val UP_COLOR = 0xFF30D158.toInt()
        const val DOWN_COLOR = 0xFFFF453A.toInt()
        const val TOAST_MS = 6000L
        const val FADE_MS = 800L
        /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
        const val SHADOW_BLUR = 26f
        const val DEEP_BLUR = 30f
        const val SKY = 0
        const val HERO = 1
        const val STATUS = 2
        const val PLATTER = 3
        const val TILES = 4
        const val DRAWER = 5
        const val SHEET = 6
        const val TOAST = 7
        const val Z_STATUS = 0
        const val Z_HERO = 1
        const val Z_SHELF = 2
        const val Z_DRAWER = 3
        val LOADER: ExecutorService = Executors.newSingleThreadExecutor()
        // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
        // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
        const val L_TITLE_BASE = 130f
        const val L_TABBAR_TOP = 158f
        const val L_TABBAR_H = 68f
        const val L_CARDS_TOP = 262f
        const val L_CARD_H = 442f
        const val L_TALL_W = 320f
        const val L_CARD_GAP = 40f
        const val L_BUTTON = 68f
        const val L_BUTTON_GAP = 20f
        const val L_BUTTON_CY = 103f
        const val L_PLATTER_TOP = 814f
        const val L_TILE_H = 126f
        const val L_DATE_BASE = 330f
        const val L_CLOCK_BASE = 540f
        const val L_WEATHER_BASE = 626f
        const val L_QUOTES_BASE = 694f
        const val L_INK = 0xFF1D1D1F.toInt()
        const val L_DIM = 0xFF6E6E73.toInt()
        const val L_FAINT = 0xFF8E8E93.toInt()
        val TABS = arrayOf("Explorar", "Filmes", "Séries")
        val TABS_RU = arrayOf("Обзор", "Фильмы", "Сериалы")
        val TABS_EN = arrayOf("Explore", "Movies", "Shows")
        val TABS_ES = arrayOf("Explorar", "Películas", "Series")
        val TABS_DE = arrayOf("Entdecken", "Filme", "Serien")
        val TAB_KIND = arrayOf(null, "movie", "tv")
        const val Z_TOP = 4
        const val Z_CARDS = 5
        const val NIGHT_MS = 2000L

        /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
        val TILE_STYLE = HashMap<String, IntArray>().apply {
            put("netflix", intArrayOf(0xFF0A0A0A.toInt(), 0xFF000000.toInt(), 0))
            put("youtube", intArrayOf(0xFF1C1C1E.toInt(), 0xFF09090A.toInt(), 0))
            put("globoplay", intArrayOf(0xFFFF8A1F.toInt(), 0xFFFF2D5A.toInt(), 1))
            put("prime", intArrayOf(0xFF2AA8FF.toInt(), 0xFF0A68D4.toInt(), 1))
            put("disney", intArrayOf(0xFF173584.toInt(), 0xFF08143F.toInt(), 0))
            put("max", intArrayOf(0xFF5C30E8.toInt(), 0xFF221684.toInt(), 0))
            put("appletv", intArrayOf(0xFF1C1C1E.toInt(), 0xFF000000.toInt(), 0))
        }
        const val HOLD_MS = 500L
        const val WIGGLE_MS = 300L
        const val LIFT_GROW = 0.08f
        const val WIGGLE_DEG = 1.5f

        /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
        const val GRID = 9
        const val WIDGETS = 8
    }

    private val activity: Activity
    private val scene = Scene()
    private val data: HomeData
    private val recs: Recs
    private val click: Click
    private val handler = Handler(Looper.getMainLooper())
    private val ui: SharedPreferences
    private val tvInputs: TvInputManager?
    private val spoken = Spoken(this, object : Spoken.Source {
        override fun items(out: MutableList<Int>) { a11yItems(out) }
        override fun text(id: Int): String = a11yText(id)
        override fun bounds(id: Int, out: Rect): Boolean = a11yBounds(id, out)
        override fun focused(): Int = a11yFocused()
        override fun focus(id: Int) { a11yFocus(id) }
        override fun click(id: Int) { activate() }
        override fun clickable(id: Int): Boolean = (id - 1) / 1000 != WIDGETS
    })
    private val a11yRect = RectF()
    private val sky: Layer
    private val heroLayer: Layer
    private val status: Layer
    private val platter: Layer
    private val tiles: Layer
    private val drawerLayer: Layer
    private val sheetLayer: Layer
    private val toastLayer: Layer
    private var zone = Z_SHELF

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private var nightTarget = false
    private var night = 0f     // 0 = day, 1 = night
    private var nightFrom = 0f
    private var nightAt = 0L
    private val glassNight = IntArray(Backdrop.GW * Backdrop.GH)
    private val frostNight = IntArray(Backdrop.FW * Backdrop.FH)
    private val neutralGlassPx = IntArray(Backdrop.GW * Backdrop.GH)
    private val neutralFrostPx = IntArray(Backdrop.FW * Backdrop.FH)
    private var nightPair = false          // the glass holds both photos (day and night), blended by `night`
    private var nightGradient: Shader? = null

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private var tab = 0
    private var topIndex = 0
    private var cardIndex = 0
    private var widgetIndex = 0    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private var cards: List<Recs.Pick> = ArrayList()
    private val topFocus = Array(6) { Spring(0.26f, 0.85f, 0f) }
    private val topRects = Array(6) { RectF() }
    private val cardFocus = Array(3) { Spring(0.32f, 0.78f, 0f) }
    private val cardRects = Array(3) { RectF() }
    private val lightButtons = ArrayList<HomeApps.Item>()
    private lateinit var lTitle: Paint
    private lateinit var lTab: Paint
    private lateinit var lTabOn: Paint
    private lateinit var lCardTitle: Paint
    private lateinit var lCardMeta: Paint
    private lateinit var lQuote: Paint
    private lateinit var lTip: Paint
    private lateinit var lDate: Paint
    private lateinit var lClock: Paint
    private lateinit var lWeather: Paint
    private lateinit var lClockQuote: Paint
    private lateinit var lBattery: Paint
    private var lightGradient: Shader? = null
    // Day and night pictures for when there is no photo (assets/backgrounds/, from app/devices/<device>); null when
    // missing.
    private var skyLight: Bitmap? = null
    private var skyDark: Bitmap? = null

    // Shelf
    private var shelf: MutableList<HomeApps.Item> = ArrayList()
    private var tileFocus = emptyArray<Spring>()
    private var tileRects = emptyArray<RectF>()
    private val crispLogos = HashMap<String, Bitmap>()   // logos already sized for the focused tile
    private val whiteLogo = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
    private val platterRect = RectF()
    private var shelfIndex = 0

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private var reordering = false
    private var reorderAnim = false
    private var okHeld = false
    private var holdArmed = false
    private var liftIndex = -1
    private var reorderAt = 0L
    private var wiggle = 0f
    private var tileSlide = emptyArray<Spring>()
    private val moveLift = Spring(0.3f, 0.72f, 0f)
    private var reorderOrigin: MutableList<HomeApps.Item>? = null
    private val holdStart = Runnable { if (okDown && holdArmed) startReorder() }

    // Corner capsule
    private var statusItems: List<HomeApps.Item> = ArrayList()
    private var statusFocus = emptyArray<Spring>()
    private var statusRects = emptyArray<RectF>()
    private val capsuleRect = RectF()
    private var capsuleInfoW = 0f
    private var statusIndex = 0

    // Hero and background
    private var picks: List<Recs.Pick> = ArrayList()
    private var pickIndex = 0
    private var heroSlide = 1
    private var hero: Hero? = null
    private var heroFrom: Hero? = null
    private var heroFadeAt = 0L
    private var bgFadeAt = 0L
    private val heroFocus = Spring(0.28f, 0.85f, 0f)
    private val heroButton = RectF()
    private var bgFrom: Backdrop? = null     // null = gradient (no photo)
    private var bgTo: Backdrop? = null
    private val frostMix = IntArray(Backdrop.FW * Backdrop.FH)

    // Bottom drawer
    private var drawerItems: List<HomeApps.Item> = ArrayList()
    private var drawerInputs = 0          // how many leading items are inputs (the rest are shortcuts)
    private var drawerFocus = emptyArray<Spring>()
    private var drawerRects = emptyArray<RectF>()
    private val weatherCard = RectF()
    private val quotesCard = RectF()
    private val drawer = Spring(0.46f, 0.92f, 0f)
    private var drawerOpen = false
    private var drawerIndex = 0

    // "All Apps" sheet
    private var others: List<HomeApps.Item> = ArrayList()
    private var gridFocus = emptyArray<Spring>()
    private var gridLabels: Array<String?>? = null
    private val sheet = Spring(0.38f, 0.9f, 0f)
    private val scroll = Spring(0.34f, 1f, 0f)
    private val focusedCell = RectF()
    private var sheetOpen = false
    private var gridIndex = 0

    // Toasts
    private val toasts = ArrayDeque<String>()
    private var toast: StaticLayout? = null
    private var toastAt = 0L

    // Frames
    private var running = false
    private var tilesDirty = false
    private var statusDirty = false
    private var heroDirty = false
    private var drawerDirty = false
    private var gridDirty = false
    private var motionDirty = true
    private var drawerWasMoving = false
    private var idle = false
    private var lastNanos = 0L
    private var resumedAt = 0L
    private var lastStep = 0L
    private var w = 0
    private var h = 0
    private var k = 1f
    private var clockText = ""
    private var dateText = ""
    private var weatherLine = ""
    // XGIMI's Play 6: its battery's level and icon (Xgimi.battery), next to the round buttons; empty elsewhere.
    private var batteryText = ""
    private var batterySymbol = ""
    private var weatherLineAt = -1L
    private var weatherLinePrefs = -1
    private var weatherLineLanguage = ""
    private var clockMinute = -1L
    private var battery: Power.Battery? = null   // Rokid Station: the battery in the corner (null: none to show)
    private val batteryEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val b = Power.battery(i)
            val now = battery
            val same = if (b == null) now == null else now != null && b.level == now.level && b.plugged == now.plugged
            if (same) return
            battery = b
            status.invalidate()
        }
    }
    private var okDown = false
    private var backDown = false

    private lateinit var labelPaint: Paint
    private lateinit var metaPaint: Paint
    private lateinit var clockPaint: Paint
    private lateinit var buttonPaint: Paint
    private lateinit var capsuleText: Paint
    private lateinit var tipPaint: Paint
    private lateinit var quotePaint: Paint
    private lateinit var staleQuote: Paint
    private lateinit var rowTitle: Paint
    private lateinit var drawerLabel: Paint
    private lateinit var cardTitle: Paint
    private lateinit var cardText: Paint
    private lateinit var cardDim: Paint
    private lateinit var cardDimRight: Paint
    private lateinit var bigTemp: Paint
    private lateinit var hourPaint: Paint
    private lateinit var hourTemp: Paint
    private lateinit var pricePaint: Paint
    private lateinit var changePaint: Paint
    private lateinit var sheetTitle: Paint
    private lateinit var batteryPaint: Paint
    private lateinit var titlePaint: TextPaint
    private lateinit var gridLabel: TextPaint
    private lateinit var toastPaint: TextPaint
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowSoft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowDeep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadows = Shadows()
    private val imageShaders = WeakHashMap<Bitmap, BitmapShader>()
    private val imageMatrix = Matrix()
    private val imageFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val tmp = RectF()
    private val cell = RectF()
    private val screen = RectF()
    private val fitPaint = TextPaint()
    private val gridCell = RectF()
    private var tileShaders = arrayOfNulls<LinearGradient>(0)
    private var tileGlow: LinearGradient? = null
    private var cardSheen: LinearGradient? = null
    private var tileShaderH = 0f
    private var cardSheenH = 0f
    private var lightSheens = arrayOfNulls<LinearGradient>(0)
    private var lightRims = arrayOfNulls<LinearGradient>(0)
    private var lightShaderW = FloatArray(0)
    private var veilFilter: ColorFilter? = null
    private var veilAmount = -1f
    private var veilColor = 0
    private var textWeather: HomeData.Weather? = null
    private var textPricesAt = -1L
    private var textMinute = -1L
    private var textPrefs = -1
    private var textLanguage: String? = null
    private lateinit var weatherTitle: String
    private lateinit var nowTemp: String
    private lateinit var highLow: String
    private lateinit var sunriseText: String
    private lateinit var sunsetText: String
    private lateinit var staleText: String
    private val hourText = arrayOfNulls<String>(6)
    private val hourTempText = arrayOfNulls<String>(6)
    private val priceText = arrayOfNulls<String>(HomeData.COINS.size)
    private val changeText = arrayOfNulls<String>(HomeData.COINS.size)

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private class Hero {
        var label = ""
        var title = ""
        var meta = ""
        var clock = false
        var button = false
        var dots = 0
        var dot = 0
        var titleLayout: StaticLayout? = null

        fun sameText(o: Hero?): Boolean =
            o != null && clock == o.clock && label == o.label && title == o.title && meta == o.meta
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private inner class Layer(c: Context, private val kind: Int) : View(c) {
        override fun onDraw(c: Canvas) {
            if (!this@HomeView::labelPaint.isInitialized) return
            c.translate(-left.toFloat(), -top.toFloat())
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0f, if (kind == DRAWER || kind == STATUS) 0f else translationY)
            when (kind) {
                SKY -> drawSky(c)
                HERO -> drawLightTop(c)
                STATUS -> drawLightButtons(c)
                PLATTER -> drawPlatter(c)
                TILES -> drawTiles(c)
                DRAWER -> drawDrawer(c)
                SHEET -> drawSheet(c)
                else -> drawToast(c)
            }
            scene.setOffset(0f, 0f)
        }
    }

    private val reloadSoon = Runnable { reload() }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String?, state: Int) { reloadSoon() }
        override fun onInputAdded(inputId: String?) { reloadSoon() }
        override fun onInputRemoved(inputId: String?) { reloadSoon() }
    }

    private fun reloadSoon() {
        handler.removeCallbacks(reloadSoon)
        handler.postDelayed(reloadSoon, 500)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            wake()
            data.refreshIfStale()
            recs.refreshIfStale()
            checkNotices()
            checkBattery()
            updatePalette()
            checkNight(true)
            updatePicks()      // three new ones every hour
            handler.postDelayed(this, 15_000)
        }
    }

    init {
        activity = a
        data = HomeData.get(a)
        recs = Recs.get(a)
        click = Click(a)
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE)
        tvInputs = a.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager?
        isFocusable = true
        sky = Layer(a, SKY)
        heroLayer = Layer(a, HERO)
        status = Layer(a, STATUS)
        platter = Layer(a, PLATTER)
        tiles = Layer(a, TILES)
        drawerLayer = Layer(a, DRAWER)
        sheetLayer = Layer(a, SHEET)
        toastLayer = Layer(a, TOAST)
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (v in arrayOf<View>(heroLayer, status, platter, tiles, drawerLayer, sheetLayer)) {
            v.setLayerType(if (Ui.LEGACY) LAYER_TYPE_NONE else LAYER_TYPE_HARDWARE, null)
        }
        for (v in arrayOf<View>(sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer)) {
            v.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO   // TalkBack reads the items (Spoken)
            addView(v)
        }
        contentDescription = Config.APP_NAME
        drawerLayer.visibility = INVISIBLE
        makeLightButtons()
        loadSky(a.applicationContext)
        recs.setLight(true)
        checkNight(false)
        sheetLayer.visibility = INVISIBLE
        scene.setMotion(0.6f, 0f)
        updatePalette()
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false))
        setStatusItems(statusList(a))
        setDrawerItems(drawerList(a, false))
    }

    // MARK: - Lifecycle (called by HomeActivity)

    fun resume() {
        if (running) return
        running = true
        checkNight(false)
        resumedAt = SystemClock.uptimeMillis()
        lastNanos = 0
        lastStep = 0
        data.addListener(this)
        recs.addListener(this)
        data.refreshIfStale()
        recs.refreshIfStale()
        tvInputs?.registerCallback(inputCallback, handler)
        if (Power.available()) {
            battery = Power.battery(Ui.register(context, batteryEvents, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        handler.postDelayed(tick, 15_000)
        checkNotices()
        checkBattery()
        reload()
        updatePalette()
        updatePicks()
        if (sheetOpen) closeSheet()
        sheet.snap(sheet.target)
        clockMinute = -1   // the time zone may have changed meanwhile (Time Zone, on XGIMI projectors)
        motionDirty = true
        invalidateAll()
        idle = false
        Choreographer.getInstance().postFrameCallback(this)
        spoken.readAgain()
    }

    fun pause() {
        if (!running) return
        running = false
        cancelReorder(true)
        handler.removeCallbacks(holdStart)
        holdArmed = false
        okHeld = false
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(reloadSoon)
        data.removeListener(this)
        recs.removeListener(this)
        tvInputs?.unregisterCallback(inputCallback)
        if (Power.available()) context.unregisterReceiver(batteryEvents)
    }

    fun destroy() {
        pause()
        click.release()
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    fun reset(animated: Boolean) {
        wake()
        cancelReorder(!animated)
        zone = Z_SHELF
        shelfIndex = 0
        sheetOpen = false
        drawerOpen = false
        if (animated) {
            sheet.target = 0f
            drawer.target = 0f
        } else {
            sheet.snap(0f)
            drawer.snap(0f)
        }
        if (pickIndex != 0 && picks.isNotEmpty()) {
            pickIndex = 0
            updatePicks()
        }
        if (tab != 0) selectTab(0)
        retarget(!animated)
        motionDirty = true
    }

    /**
     * The corner buttons, with their names in the current language (Live TV only where there is live TV; HDMI Inputs
     * not on the Rokid Station, which has none).
     */
    private fun makeLightButtons() {
        lightButtons.clear()
        if (HomeApps.liveTv(context) != null) {
            lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"))
        }
        if (!HomeMode.ROKID) {
            lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"))
        }
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"))
    }

    private fun tabs(): Array<String> = if (Ui.ru) TABS_RU else if (Ui.es) TABS_ES else if (Ui.de) TABS_DE
        else if (Ui.en) TABS_EN else TABS

    override fun onData() {
        wake()
        updatePalette()
        checkNight(true)
        layoutStatus()
        heroDirty = true
        statusDirty = true
        drawerDirty = true
        platter.invalidate()
    }

    override fun onRecs() {
        wake()
        updatePicks()
    }

    private fun invalidateAll() {
        for (v in arrayOf<View>(sky, heroLayer, status, platter, tiles, drawerLayer)) v.invalidate()
    }

    // MARK: - Size and layers

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = MeasureSpec.getSize(heightSpec)
        val kk = width / 1920f
        measure(sky, width, height)
        measure(heroLayer, width, Math.round(790 * kk))
        measure(status, Math.round(1160 * kk), Math.round(240 * kk))
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk))
        measure(tiles, width, Math.round(270 * kk))
        measure(drawerLayer, width, Math.round(600 * kk))
        measure(sheetLayer, width, height)
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk))
        setMeasuredDimension(width, height)
    }

    private fun measure(v: View, width: Int, height: Int) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val kk = width / 1920f
        sky.layout(0, 0, width, height)
        place(heroLayer, 0, 0)
        place(status, width - status.measuredWidth, 0)
        place(platter, 0, Math.round(750 * kk))
        place(tiles, 0, Math.round(790 * kk))
        place(drawerLayer, 0, Math.round(470 * kk))
        sheetLayer.layout(0, 0, width, height)
        sheetLayer.pivotX = width / 2f
        sheetLayer.pivotY = height / 2f
        place(toastLayer, (width - toastLayer.measuredWidth) / 2, 0)
    }

    private fun place(v: View, x: Int, y: Int) {
        v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        w = width
        h = height
        k = w / 1920f
        screen.set(0f, 0f, w.toFloat(), h.toFloat())
        scene.setSize(w, h)
        makePaints()
        gridLabels = null
        clockMinute = -1
        updateClock()
        layoutShelf()
        layoutStatus()
        layoutDrawer()
        layoutLight()
        hero = null
        heroFrom = null
        updatePicks()
        motionDirty = true
        wake()
    }

    private fun makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF.toInt())
        labelPaint.letterSpacing = 0.1f
        titlePaint = TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF.toInt()))
        titlePaint.letterSpacing = 0.005f
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF.toInt())
        metaPaint.letterSpacing = 0.014f
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF.toInt())
        clockPaint.letterSpacing = -0.01f
        for (p in arrayOf(labelPaint, titlePaint, metaPaint, clockPaint)) p.setShadowLayer(22 * k, 0f, 2 * k, 0x59000000)
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK)
        lTitle.letterSpacing = 0.004f
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM)
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK)
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK)
        lCardTitle.letterSpacing = 0.012f
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM)
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT)
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK)
        batteryPaint = Ui.numbers(Ui.semibold, 26 * k, L_INK)
        lTip.textAlign = Paint.Align.CENTER
        lBattery = Ui.numbers(Ui.semibold, 27 * k, L_INK)
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM)
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK)
        lClock.letterSpacing = -0.01f
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM)
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM)
        lightGradient = null
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK)
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF.toInt())
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF.toInt())
        tipPaint.textAlign = Paint.Align.CENTER
        tipPaint.setShadowLayer(16 * k, 0f, 1 * k, 0x99000000.toInt())
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF.toInt())
        quotePaint.setShadowLayer(14 * k, 0f, 1 * k, 0x99000000.toInt())
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF.toInt())
        staleQuote.textAlign = Paint.Align.RIGHT
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF.toInt())
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF.toInt())
        drawerLabel.textAlign = Paint.Align.CENTER
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF.toInt())
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF.toInt())
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF.toInt())
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF.toInt())
        cardDimRight.textAlign = Paint.Align.RIGHT
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF.toInt())
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF.toInt())
        hourPaint.textAlign = Paint.Align.CENTER
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF.toInt())
        hourTemp.textAlign = Paint.Align.CENTER
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF.toInt())
        pricePaint.textAlign = Paint.Align.RIGHT
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF.toInt())
        changePaint.textAlign = Paint.Align.CENTER
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF.toInt())
        sheetTitle.textAlign = Paint.Align.CENTER
        gridLabel = TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF.toInt()))
        gridLabel.textAlign = Paint.Align.CENTER
        toastPaint = TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF.toInt()))
        shadow.maskFilter = BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL)
        shadowSoft.maskFilter = BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL)
        shadowDeep.maskFilter = BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL)
        halo.maskFilter = BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL)
        border.style = Paint.Style.STROKE
        border.strokeWidth = 2 * k
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = 2.5f * k
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 1 / 60f else min(0.05f, (frameTimeNanos - lastNanos) / 1e9f)
        lastNanos = frameTimeNanos
        val up = SystemClock.uptimeMillis()

        var tilesMoving = step(tileFocus, dt)
        if (reorderAnim) tilesMoving = tilesMoving or stepReorder(dt, up)
        val statusMoving = step(statusFocus, dt)
        var heroMoving = heroFocus.step(dt)
        val topMoving = step(topFocus, dt)
        val cardsMoving = step(cardFocus, dt)
        if (topMoving) status.invalidate()
        heroMoving = heroMoving or (topMoving or cardsMoving)
        val drawerItemsMoving = step(drawerFocus, dt)
        val gridMoving = step(gridFocus, dt) or scroll.step(dt)
        val drawerMoving = drawer.step(dt)
        val sheetMoving = sheet.step(dt)
        val toastFading = tickToast(up)
        val heroFading = tickHeroFade(up)
        val bgFading = tickBackgroundFade(up)
        val nightMoving = tickNight(up)

        if (drawerMoving || sheetMoving || motionDirty) updateMotion()
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate()
        if (statusMoving || statusDirty || bgFading) status.invalidate()
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate()
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate()
        if (gridMoving || gridDirty) sheetLayer.invalidate()
        if (drawerMoving || bgFading) sky.invalidate()
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate()
        drawerWasMoving = drawerMoving
        tilesDirty = false
        statusDirty = false
        heroDirty = false
        drawerDirty = false
        gridDirty = false
        motionDirty = false
        if (updateClock()) {
            layoutStatus()
            status.invalidate()
            val hr = hero
            if (hr != null && hr.clock) {
                refreshHero(false)
                heroLayer.invalidate()
            }
            if (!picksOn()) heroLayer.invalidate()
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        val busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty()
        idle = !busy
        if (busy) Choreographer.getInstance().postFrameCallback(this)
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000)
    }

    private fun wake() {
        if (!running || !idle) return
        idle = false
        lastNanos = 0
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(springs: Array<Spring>, dt: Float): Boolean {
        var moving = false
        for (s in springs) moving = moving or s.step(dt)
        return moving
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private fun updateMotion() {
        val d = drawer.value
        val dc = Ui.clamp(d)
        val s = Ui.clamp(sheet.value)
        val home = 1 - s
        val top = home * (1 - smoothstep(0f, 0.32f, dc))
        val shift = SHIFT * k
        val lift = -d * shift
        show(heroLayer, top, lift)
        show(status, top, lift)
        show(platter, home, lift)
        show(tiles, home, lift)
        val da = home * smoothstep(0.1f, 0.6f, dc)
        drawerLayer.translationY = (1 - d) * shift
        drawerLayer.alpha = da
        drawerLayer.visibility = if (da > 0.002f) VISIBLE else INVISIBLE
        sheetLayer.visibility = if (s > 0.001f || sheetOpen) VISIBLE else INVISIBLE
        sheetLayer.alpha = s
        val scale = 1.04f - 0.04f * s
        sheetLayer.scaleX = scale
        sheetLayer.scaleY = scale
    }

    private fun show(v: View, alpha: Float, translationY: Float) {
        v.alpha = alpha
        v.translationY = translationY
        v.visibility = if (alpha > 0.002f) VISIBLE else INVISIBLE
    }

    private fun updateClock(): Boolean {
        val now = System.currentTimeMillis()
        val minute = now / 60_000
        if (minute == clockMinute || !::clockPaint.isInitialized) return false
        clockMinute = minute
        clockText = Ui.clock(now)
        dateText = Ui.date(now)
        return true
    }

    private fun updatePalette() {
        val wx = data.weather
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset)
        scene.applyPalette()
    }

    // MARK: - Recommendations: hero and background

    private fun currentPick(): Recs.Pick? = if (picks.isEmpty()) null else picks[min(pickIndex, picks.size - 1)]

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private fun updatePicks() {
        if (!::labelPaint.isInitialized) return
        updateCards()
    }

    private fun samePicks(a: List<Recs.Pick>, b: List<Recs.Pick>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (Recs.key(a[i]) != Recs.key(b[i])) return false
        return true
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private fun refreshHero(animate: Boolean) {
        val next = makeHero()
        val old = hero
        if (old != null && next.sameText(old) && old.dots == next.dots && old.dot == next.dot && old.button == next.button) {
            return
        }
        val other = old != null && (old.clock != next.clock || old.title != next.title)
        if (animate && other) {
            heroFrom = old
            heroFadeAt = SystemClock.uptimeMillis()
        }
        val layoutChanged = old == null || old.clock != next.clock
        hero = next
        heroDirty = true
        if (layoutChanged) layoutStatus()
    }

    private fun makeHero(): Hero {
        val h = Hero()
        val p = currentPick()
        if (p == null) {
            h.clock = true
            h.label = Ui.date(System.currentTimeMillis()).uppercase(Ui.locale())
            return h
        }
        h.label = Recs.periodLabel()
        h.title = p.title ?: ""
        h.meta = p.meta ?: ""
        h.button = p.pkg != null
        h.dots = picks.size
        h.dot = pickIndex
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length, titlePaint, max(1, Math.round(HERO_TITLE_W * k)))
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.94f)
            .setIncludePad(false)
            .build()
        return h
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private fun browse(dir: Int): Boolean {
        if (picks.size < 2) return false
        var next = pickIndex
        for (i in 0 until picks.size) {
            next = (next + dir + picks.size) % picks.size
            if (zone != Z_HERO || picks[next].pkg != null) break
        }
        if (next == pickIndex) return false
        pickIndex = next
        heroSlide = dir
        refreshHero(true)
        val b = recs.backdrop(currentPick())
        if (b != null) showBackdrop(b)
        spoken.focusMoved()
        return true
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private fun showBackdrop(b: Backdrop?) {
        if (b === bgTo) return
        val up = SystemClock.uptimeMillis()
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0L || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo
        bgTo = b
        bgFadeAt = up
        nightPair = false
        if (b == null || glassOf(b) == null) return
        val from = bgFrom
        if (from == null || glassOf(from) == null) {
            scene.setFrost(frostOf(b))
            scene.setGlassImage(glassOf(b))
        } else {
            scene.setGlassImages(glassOf(from), glassOf(b))
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private fun glassOf(b: Backdrop): IntArray? {
        val nightGlass = b.nightGlass
        if (nightGlass == null || night <= 0.001f) return b.lightGlass
        if (night >= 0.999f) return nightGlass
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b !== bgTo) return if (night < 0.5f) b.lightGlass else nightGlass
        val lightGlass = b.lightGlass!!
        for (i in glassNight.indices) glassNight[i] = mix(lightGlass[i], nightGlass[i], night)
        return glassNight
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private fun neutralGlass() {
        val color = blend(0xFFEBEBF0.toInt(), 0xFF141416.toInt(), night)
        neutralFrostPx.fill(color)
        neutralGlassPx.fill(color)
        scene.setFrost(neutralFrostPx)
        scene.setGlassImage(neutralGlassPx)
        nightPair = false
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private fun frostOf(b: Backdrop): IntArray {
        val nightFrost = b.nightFrost
        if (nightFrost == null || night <= 0.001f) return b.frost
        if (night >= 0.999f) return nightFrost
        if (b !== bgTo) return if (night < 0.5f) b.frost else nightFrost
        val frost = b.frost
        for (i in frostNight.indices) frostNight[i] = mix(frost[i], nightFrost[i], night)
        return frostNight
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private fun checkNight(animate: Boolean) {
        val want = data.isNight(System.currentTimeMillis())
        if (want == nightTarget) return
        nightTarget = want
        if (animate && running) {
            nightFrom = night
            nightAt = SystemClock.uptimeMillis()
        } else {
            night = if (want) 1f else 0f
            nightAt = 0
            nightChanged()
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private fun tickNight(up: Long): Boolean {
        if (nightAt == 0L) return false
        val t = Ui.clamp((up - nightAt).toFloat() / NIGHT_MS)   // float division: a 2 s fade, not a jump at the end
        val to = if (nightTarget) 1f else 0f
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t)
        if (t >= 1) {
            night = to
            nightAt = 0
        }
        nightChanged()
        return true
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private fun nightChanged() {
        val to = bgTo
        if (bgFadeAt == 0L && to != null && to.lightGlass != null) {
            scene.setFrost(frostOf(to))
            if (nightAt != 0L && to.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(to.lightGlass, to.nightGlass)
                    nightPair = true
                }
                scene.setGlassMix(night)
            } else {
                scene.setGlassImage(glassOf(to))
                nightPair = false
            }
        } else if (bgFadeAt == 0L && to == null) {
            neutralGlass()
        }
        heroDirty = true
        statusDirty = true
        tilesDirty = true
        drawerDirty = true
        sky.invalidate()
        platter.invalidate()
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private fun dark(): Float = night

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private fun tone(onLight: Int, onDark: Int): Int = blend(onLight, onDark, dark())

    private fun tone(onLight: Float, onDark: Float): Float = onLight + (onDark - onLight) * dark()

    /** Text colors for the current night value (dark by day, white at night). */
    private fun applyTone() {
        lTitle.color = tone(L_INK, WHITE)
        lTab.color = tone(L_DIM, 0xFF8E8E93.toInt())
        lTabOn.color = tone(L_INK, WHITE)
        lCardTitle.color = tone(L_INK, WHITE)
        lCardMeta.color = tone(L_DIM, 0xFF98989D.toInt())
        lQuote.color = tone(L_FAINT, 0xB8FFFFFF.toInt())
        lTip.color = tone(L_INK, WHITE)
        lDate.color = tone(L_DIM, 0xFF98989D.toInt())
        lClock.color = tone(L_INK, WHITE)
        lWeather.color = tone(L_DIM, 0xFF98989D.toInt())
        lClockQuote.color = tone(L_DIM, 0xFF98989D.toInt())
        staleQuote.color = tone(L_FAINT, 0x99FFFFFF.toInt())
    }

    private fun tickBackgroundFade(up: Long): Boolean {
        if (bgFadeAt == 0L) return false
        val e = fadeEase(bgFadeAt, up)
        val to = bgTo
        val from = bgFrom
        if (to != null && from != null && glassOf(from) != null && glassOf(to) != null) {
            if (drawerLayer.visibility == VISIBLE || toast != null) {
                val fromFrost = frostOf(from)
                val toFrost = frostOf(to)
                for (i in frostMix.indices) frostMix[i] = mix(fromFrost[i], toFrost[i], e)
                scene.setFrost(frostMix)
            }
            scene.setGlassMix(e)
        }
        if (e >= 1) {
            bgFadeAt = 0
            bgFrom = null
            val now = bgTo
            if (now == null) {
                neutralGlass()
            } else {
                scene.setFrost(frostOf(now))
                if (glassOf(now) != null) scene.setGlassImage(glassOf(now))
            }
        }
        return true
    }

    private fun tickHeroFade(up: Long): Boolean {
        if (heroFadeAt == 0L) return false
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0
            heroFrom = null
        }
        return true
    }

    private fun fadeEase(start: Long, up: Long): Float = fadeEase(start, up, FADE_MS.toFloat())

    private fun fadeEase(start: Long, up: Long, duration: Float): Float {
        val t = Ui.clamp((up - start) / duration)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = Math.round(((a shr 16) and 255) * (1 - f) + ((b shr 16) and 255) * f)
        val g = Math.round(((a shr 8) and 255) * (1 - f) + ((b shr 8) and 255) * f)
        val bl = Math.round((a and 255) * (1 - f) + (b and 255) * f)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    // MARK: - Background

    private fun drawSky(c: Canvas) {
        drawLightSky(c)
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private fun veil(amount: Float, color: Int): ColorFilter? {
        if (amount <= 0.002f) return null
        val a = Math.round(amount * 255) / 255f
        if (a == veilAmount && color == veilColor) return veilFilter
        veilAmount = a
        veilColor = color
        val keep = 1 - a
        val filter = ColorMatrixColorFilter(floatArrayOf(
            keep, 0f, 0f, 0f, ((color shr 16) and 255) * a,
            0f, keep, 0f, 0f, ((color shr 8) and 255) * a,
            0f, 0f, keep, 0f, (color and 255) * a,
            0f, 0f, 0f, 1f, 0f))
        veilFilter = filter
        return filter
    }

    private fun drawImage(c: Canvas, image: Bitmap?, alpha: Float) {
        if (image == null || alpha <= 0.004f) return
        imagePaint.alpha = Math.round(255 * min(1f, alpha))
        c.drawBitmap(image, null, screen, imagePaint)
    }

    // MARK: - Hero

    private fun text(c: Canvas, s: String?, x: Float, y: Float, p: Paint, color: Int, alpha: Float) {
        if (s == null || s.isEmpty()) return
        p.color = Ui.alpha(color, alpha)
        c.drawText(s, x, y, p)
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private fun statusList(c: Context): List<HomeApps.Item> {
        val list = ArrayList<HomeApps.Item>()
        list.addAll(HomeApps.shortcuts(c))
        list.add(HomeApps.settings())
        return list
    }

    private fun setStatusItems(list: List<HomeApps.Item>) {
        var same = list.size == statusItems.size
        var j = 0
        while (same && j < list.size) {
            same = list[j].key == statusItems[j].key
            j++
        }
        val focusedKey = if (statusIndex < statusItems.size) statusItems[statusIndex].key else null
        statusItems = list
        if (!same || statusFocus.size != list.size) {
            statusFocus = Array(list.size) { Spring(0.26f, 0.85f, 0f) }
            statusRects = Array(list.size) { RectF() }
            var index = min(statusIndex, max(0, list.size - 1))
            for (i in statusFocus.indices) {
                if (list[i].key == focusedKey) index = i   // a device came on: keep focus on the same button
            }
            statusIndex = index
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF
            aim(statusFocus, if (zone == Z_STATUS) statusIndex else -1, true)   // only the capsule's springs
            statusDirty = true
        }
        layoutStatus()
    }

    private fun showInfo(): Boolean {
        val hr = hero
        return hr != null && !hr.clock
    }

    private fun layoutStatus() {
        if (!::capsuleText.isInitialized) return
        val n = statusItems.size
        val b = BUTTON * k
        val gap = 4 * k
        val pad = 6 * k
        var info = 0f
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText)
            val wx = data.weather
            if (!wx.temp.isNaN() && Prefs.weather()) {
                info += 22 * k + Symbols.width(context, Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k +
                    capsuleText.measureText(HomeData.degrees(wx.temp))
            }
            info += 22 * k
        }
        capsuleInfoW = info
        val buttons = n * b + max(0, n - 1) * gap
        val right = CONTENT_R * k
        val top = CAPSULE_TOP * k
        val left = right - pad - buttons - (if (info > 0) info + 12 * k else pad)
        capsuleRect.set(left, top, right, top + CAPSULE_H * k)
        var x = right - pad - buttons
        val cy = capsuleRect.centerY()
        for (i in 0 until n) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2)
            x += b + gap
        }
        statusDirty = true
    }

    // MARK: - Quotes and shelf

    private fun setShelf(list: MutableList<HomeApps.Item>) {
        wake()
        cancelReorder(true)    // the list changed under the move (an app installed or removed)
        tileShaders = arrayOfNulls(0)
        var same = list.size == shelf.size
        var i = 0
        while (same && i < list.size) {
            same = list[i].key == shelf[i].key
            i++
        }
        shelf = list
        if (!same || tileFocus.size != list.size) {
            tileFocus = Array(list.size) { Spring(0.34f, 0.74f, 0f) }
            tileSlide = Array(list.size) { Spring(0.32f, 0.86f, 0f) }
            tileRects = Array(list.size) { RectF() }
            shelfIndex = min(shelfIndex, max(0, list.size - 1))
            retarget(true)
            layoutShelf()
        }
        tilesDirty = true
        platter.invalidate()
    }

    private fun layoutShelf() {
        val n = shelf.size
        val pad = SHELF_PAD * k
        val gap = TILE_GAP * k
        val full = (CONTENT_R - MARGIN) * k
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        val tw = min(L_TILE_H * 16f / 9 * k, (full - max(0, n - 1) * gap) / max(1, n))
        val th = tw * 9f / 16
        val width = n * tw + max(0, n - 1) * gap
        val left = (w - width) / 2f
        val top = (L_PLATTER_TOP + L_TILE_H) * k - th
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad)
        var x = left
        val y = top + pad
        for (i in 0 until n) {
            tileRects[i].set(x, y, x + tw, y + th)
            x += tw + gap
        }
        crispLogos.clear()
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private fun drawPlatter(c: Canvas) {
        if (shelf.isEmpty()) return
        val radius = (TILE_RADIUS + SHELF_PAD) * k
        tmp.set(platterRect)
        tmp.offset(0f, 18 * k)
        shadow.color = tone(0x1F000000, 0x52000000)
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow)
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF.toInt(), SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
            tone(30f, 34f) * k, tone(22f, 28f) * k, tone(0.65f, 0.95f), 1f)   // band and refraction: the float tone
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private inner class Quotes(val coins: IntArray, all: DoubleArray, text: Paint) {
        val prices: DoubleArray
        val size: Float = text.textSize
        val cap: Float = Ui.cap(text)
        val halfCap: Float = Ui.halfCap(text)
        val values: Array<String?>
        val glyphs: FloatArray
        val widths: FloatArray

        init {
            val n = coins.size
            prices = DoubleArray(n)
            values = arrayOfNulls(n)
            glyphs = FloatArray(n)
            widths = FloatArray(n)
            for (i in 0 until n) {
                prices[i] = all[coins[i]]
                values[i] = HomeData.compact(prices[i])
                glyphs[i] = Glyphs.width(context, HomeData.COINS[coins[i]], cap)
                widths[i] = text.measureText(values[i])
            }
        }

        fun matches(now: IntArray, all: DoubleArray, textSize: Float): Boolean {
            if (now !== coins || textSize != size) return false
            for (i in coins.indices) if (java.lang.Double.compare(all[coins[i]], prices[i]) != 0) return false
            return true
        }
    }

    private var quotes: Quotes? = null

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private fun drawQuotes(c: Canvas) {
        val clock = !picksOn()
        val right = if (platterRect.isEmpty) CONTENT_R * k else platterRect.right - SHELF_PAD * k
        applyTone()
        val text = if (clock) lClockQuote else lQuote
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        var fade = 1f
        if (!clock) for (i in 3 until 3 + lightButtons.size) fade = min(fade, 1 - Ui.clamp(topFocus[i].value))
        if (fade <= 0.01f) return
        val textColor = text.color
        text.color = Ui.alpha(textColor, fade)
        val n = HomeData.coinCount()
        if (n == 0) {
            text.color = textColor
            return
        }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        val coins = HomeData.active()
        var q = quotes
        if (q == null || !q.matches(coins, data.prices, text.textSize)) {
            q = Quotes(coins, data.prices, text)
            quotes = q
        }
        // On the tab row, at the right.
        val base = if (clock) L_QUOTES_BASE * k else (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap
        val cap = q.cap
        val gap = (if (clock) 8 else 6) * k
        val spacing = (if (clock) 32 else 24) * k
        var total = spacing * (n - 1)
        for (i in 0 until n) total += q.glyphs[i] + gap + q.widths[i]
        var x = if (clock) MARGIN * k else right - total
        val coinColor = if (clock) text.color else Ui.alpha(tone(0xB38E8E93.toInt(), 0x8CFFFFFF.toInt()), fade)
        for (i in 0 until n) {
            Glyphs.coin(context, c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor)
            x += q.glyphs[i] + gap
            c.drawText(q.values[i]!!, x, base, text)
            x += q.widths[i] + spacing
        }
        text.color = textColor
        val stale = HomeData.staleness(data.pricesAt, 5 * 60_000L)
        if (stale.isEmpty()) return
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote)
        else c.drawText(stale, right - total - 24 * k, base, staleQuote)
    }

    private fun drawTiles(c: Canvas) {
        // The focused one last: it grows over its neighbors without being covered by them.
        var top = -1
        var most = 0.02f
        for (i in 0 until shelf.size) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value
                top = i
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size && moveLift.value > 0.02f) top = liftIndex
        for (pass in 0 until 2) {
            for (i in 0 until shelf.size) {
                if ((pass == 0) == (i == top)) continue
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                val dx = if (i < tileSlide.size) tileSlide[i].value else 0f
                val angle = if (i == liftIndex) wiggle * moveLift.value else 0f
                if (dx == 0f && angle == 0f) {
                    drawShelfTile(c, i)
                    continue
                }
                c.save()
                c.translate(dx, 0f)
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY())
                drawShelfTile(c, i)
                c.restore()
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private fun drawShelfTile(c: Canvas, i: Int) {
        val item = shelf[i]
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        val l = if (i == liftIndex) max(0f, moveLift.value) else 0f
        val f = tileFocus[i].value
        val fc = Ui.clamp(f)
        val s = 1 + FOCUS_GROW * f + LIFT_GROW * l
        val base = tileRects[i]
        val bw = base.width()
        val bh = base.height()
        val r0 = TILE_RADIUS * k
        val cx = base.centerX()
        val cy = base.centerY()
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2)
        val r = r0 * s
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2)
        tmp.offset(0f, (4 + 14 * f + 6 * l) * k)
        shadowDeep.color = Ui.alpha(0xFF000000.toInt(), tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l))
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep)
        c.restore()
        val style = TILE_STYLE[item.key]
        if (item.logo != null && style != null) {
            if (tileShaders.size != shelf.size || tileShaderH != bh) {
                tileShaders = arrayOfNulls(shelf.size)
                tileShaderH = bh
                tileGlow = LinearGradient(0f, -bh / 2, 0f, 0f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = LinearGradient(0f, -bh / 2, 0f, bh / 2, style[0], style[1], Shader.TileMode.CLAMP)
            }
            c.save()
            c.translate(cx, cy)
            c.scale(s, s)
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2)
            tileFill.shader = tileShaders[i]
            tileFill.alpha = 255
            c.drawRoundRect(tmp, r0, r0, tileFill)
            if (fc > 0.01f) {
                tileFill.shader = tileGlow
                tileFill.alpha = Math.round(255 * fc)
                c.drawRoundRect(tmp, r0, r0, tileFill)
                tileFill.alpha = 255
            }
            tileFill.shader = null
            c.restore()
            drawLogo(c, item, cell, s, style[2])
        } else if (item.logo != null) {
            tileFill.shader = null
            tileFill.color = 0xFF161618.toInt()
            c.drawRoundRect(cell, r, r, tileFill)
            drawLogo(c, item, cell, s, 0)
        } else {
            val tile = item.tile
            if (tile != null) roundImage(c, tile, cell, r)
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private fun roundImage(c: Canvas, image: Bitmap, cell: RectF, r: Float) {
        if (!Ui.LEGACY) {
            clip.reset()
            clip.addRoundRect(cell, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.drawBitmap(image, null, cell, bitmapPaint)
            c.restore()
            return
        }
        var shader = imageShaders[image]
        if (shader == null) {
            shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            imageShaders[image] = shader
        }
        imageMatrix.setScale(cell.width() / image.width, cell.height() / image.height)
        imageMatrix.postTranslate(cell.left, cell.top)
        shader.setLocalMatrix(imageMatrix)
        imageFill.shader = shader
        c.drawRoundRect(cell, r, r, imageFill)
        imageFill.shader = null
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private fun drawLogo(c: Canvas, item: HomeApps.Item, box: RectF, s: Float, mode: Int) {
        val rest = s < 1 + FOCUS_GROW / 2
        val logo = crispLogo(item, mode == 2 && item.logoDark != null, rest) ?: return
        val scale = if (rest) s else s / (1 + FOCUS_GROW)
        val dw = logo.width * scale
        val dh = logo.height * scale
        var left = box.centerX() - dw / 2
        var top = box.centerY() - dh / 2
        if (if (rest) abs(scale - 1) < 0.002f else scale > 0.998f) {
            left = Math.round(left).toFloat()
            top = Math.round(top).toFloat()
        }
        tmp.set(left, top, left + dw, top + dh)
        bitmapPaint.colorFilter = if (mode == 1) whiteLogo else null
        c.drawBitmap(logo, null, tmp, bitmapPaint)
        bitmapPaint.colorFilter = null
    }

    private fun crispLogo(item: HomeApps.Item, dark: Boolean, rest: Boolean): Bitmap? {
        val src: Bitmap? = if (dark) item.logoDark else item.logo
        if (tileRects.isEmpty()) return src
        val key = "${item.key}@${System.identityHashCode(src)}@${Math.round(tileRects[0].width())}${if (rest) "@rest" else ""}"
        val cached = crispLogos[key]
        if (cached != null) return cached
        val bw = tileRects[0].width() * (1 + FOCUS_GROW)
        val bh = tileRects[0].height() * (1 + FOCUS_GROW)
        val lw = src!!.width.toFloat()
        val lh = src.height.toFloat()
        var scale = sqrt(bw * bh * 0.085f / (lw * lh * max(0.08f, item.logoInk))) * item.logoScale
        scale = min(scale, min(bw * 0.76f / lw, bh * 0.58f / lh))
        if (rest) scale /= 1 + FOCUS_GROW
        val out = downscale(src, Math.round(lw * scale), Math.round(lh * scale))
        crispLogos[key] = out
        return out
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private fun downscale(src: Bitmap, width: Int, height: Int): Bitmap {
        val w = max(1, width)
        val h = max(1, height)
        var b = src
        while (b.width / 2 >= w && b.height / 2 >= h) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle()
            b = half
        }
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (b !== src && b !== out) b.recycle()
        return out
    }

    // MARK: - Bottom drawer

    /** `glasses`: also the 2D/3D button, which asks the glasses (background only). */
    private fun drawerList(c: Context, glasses: Boolean): List<HomeApps.Item> {
        val list = HomeApps.allInputs(c)
        list.addAll(HomeApps.drawerShortcuts(c, glasses))
        return list
    }

    private fun setDrawerItems(list: List<HomeApps.Item>) {
        var inputs = 0
        while (inputs < list.size && (list[inputs].kind == HomeApps.INPUT || list[inputs].kind == HomeApps.LIVE_TV)) inputs++
        var same = list.size == drawerItems.size
        var i = 0
        while (same && i < list.size) {
            same = list[i].key == drawerItems[i].key && list[i].connected == drawerItems[i].connected
            i++
        }
        drawerItems = list
        drawerInputs = inputs
        if (drawerFocus.size != list.size) {
            drawerFocus = Array(list.size) { Spring(0.28f, 0.85f, 0f) }
            drawerRects = Array(list.size) { RectF() }
            drawerIndex = min(drawerIndex, max(0, list.size - 1))
            retarget(true)
        }
        layoutDrawer()
        if (!same) drawerDirty = true
    }

    private fun layoutDrawer() {
        val n = drawerItems.size
        val left = MARGIN * k
        val right = CONTENT_R * k
        val gap = 18 * k
        val groupGap = 56 * k
        val groups = if (drawerInputs > 0 && drawerInputs < n) 2 else 1
        val tw = min(230 * k, (right - left - max(0, n - groups) * gap - (groups - 1) * groupGap) / max(1, n))
        var x = left
        val top = ROW_TOP * k
        for (i in 0 until n) {
            if (i == drawerInputs && i > 0) x += groupGap - gap
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k)
            x += tw + gap
        }
        val cardsTop = CARDS_TOP * k
        val split = left + (right - left) * 0.585f
        val wx = Prefs.weather()
        val qt = Prefs.quotes() && HomeData.active().isNotEmpty()
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, if (qt) split - 16 * k else right, cardsTop + CARDS_H * k)
        quotesCard.set(if (wx) split + 16 * k else left, cardsTop, right, cardsTop + CARDS_H * k)
        drawerDirty = true
    }

    private fun openDrawer() {
        if (drawerItems.isEmpty()) return
        drawerOpen = true
        drawer.target = 1f
        zone = Z_DRAWER
        drawerIndex = if (shelf.isEmpty()) 0 else nearest(drawerRects, tileRects[shelfIndex].centerX())
        for (i in drawerFocus.indices) drawerFocus[i].snap(if (i == drawerIndex) 1f else 0f)
        drawerDirty = true
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.target = 0f
        zone = Z_SHELF
        for (f in drawerFocus) f.snap(0f)   // the drawer goes away: its focus doesn't need to animate
        if (shelf.isNotEmpty() && drawerIndex < drawerRects.size) {
            shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX())
        }
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private fun drawerInk(alpha: Float): Int = Ui.alpha(tone(L_INK, 0xFFFFFFFF.toInt()), alpha)

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private fun drawerGlass(c: Canvas, r: RectF, radius: Float, lightTint: Float, shadowColor: Int, drop: Float) {
        val dk = dark()
        if (dk < 0.999f) {
            tmp.set(r)
            tmp.offset(0f, drop)
            shadow.color = Ui.alpha(shadowColor, 1 - dk)
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow)
            scene.glass(c, r, radius, 0xFFFFFFFF.toInt(), lightTint, 0, 0f, 0.02f, 1f, 1 - dk)
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0f, 0.02f, 1f, dk)
    }

    private fun drawerCard(c: Canvas, r: RectF) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k)
    }

    private fun drawDrawer(c: Canvas) {
        if (drawerItems.isEmpty()) return
        rowTitle.color = tone(L_DIM, 0xB3FFFFFF.toInt())
        cardTitle.color = tone(L_DIM, 0xB3FFFFFF.toInt())
        cardText.color = drawerInk(0.95f)
        cardDim.color = tone(L_DIM, 0xADFFFFFF.toInt())
        cardDimRight.color = tone(L_FAINT, 0x99FFFFFF.toInt())
        bigTemp.color = drawerInk(1f)
        hourPaint.color = tone(L_DIM, 0xADFFFFFF.toInt())
        hourTemp.color = drawerInk(0.95f)
        pricePaint.color = drawerInk(0.95f)
        val titleBase = ROW_TITLE * k
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle)
        if (drawerInputs < drawerItems.size) {
            c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle)
        }
        var top = -1
        for (i in 0 until drawerItems.size) {
            if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i
        }
        for (i in 0 until drawerItems.size) if (i != top) drawDrawerTile(c, i)
        if (top >= 0) drawDrawerTile(c, top)
        if (Prefs.weather()) drawWeatherCard(c, weatherCard)
        if (Prefs.quotes() && HomeData.active().isNotEmpty()) drawQuotesCard(c, quotesCard)
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private fun drawDrawerTile(c: Canvas, i: Int) {
        val item = drawerItems[i]
        val f = Ui.clamp(drawerFocus[i].value)
        val s = 1 + 0.07f * f
        val base = drawerRects[i]
        val cx = base.centerX()
        val cy = base.centerY()
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2)
        val r = 20 * k * s
        if (f > 0.01f) {
            tmp.set(cell)
            tmp.offset(0f, 12 * k * f)
            shadow.color = Ui.alpha(0xFF000000.toInt(), 0.45f * f)
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow)
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k)
        if (f > 0.01f) {
            fill.shader = null
            fill.color = Ui.alpha(WHITE, f)
            c.drawRoundRect(cell, r, r, fill)
        }
        val off = item.kind == HomeApps.INPUT && !item.connected
        val ink = blend(tone(if (off) 0xFF8E8E93.toInt() else L_INK, if (off) 0x6BFFFFFF else 0xF2FFFFFF.toInt()), INK, f)
        Symbols.draw(context, c, item.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink)
        drawerLabel.color = ink
        drawerLabel.textSize = 23 * k * s
        val room = cell.width() - 16 * k
        val wide = drawerLabel.measureText(item.label)
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.textSize = max(18 * k * s, 23 * k * s * room / wide)
        fitPaint.set(drawerLabel)
        val label = TextUtils.ellipsize(item.label, fitPaint, room, TextUtils.TruncateAt.END).toString()
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel)
        drawerLabel.textSize = 23 * k
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private fun drawWeatherCard(c: Canvas, r: RectF) {
        drawerCard(c, r)
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey()) { cc -> drawWeather(cc, r) }
        else drawWeather(c, r)
    }

    private fun drawWeather(c: Canvas, r: RectF) {
        refreshDrawerText()
        val x = r.left + 34 * k
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle)
        val wx = textWeather!!
        if (wx.temp.isNaN()) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim)
            return
        }
        val symbol = Symbols.weather(wx.code, wx.isDay)
        val sw = Symbols.width(context, symbol, 52 * k)
        Symbols.draw(context, c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f))
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp)
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText)
        c.drawText(highLow, x, r.top + 258 * k, cardDim)
        val split = r.left + 392 * k
        fill.shader = null
        fill.color = tone(0x1A000000, 0x24FFFFFF)
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill)
        val hx = split + 30 * k
        val hw = (r.right - 30 * k - hx) / 6
        for (i in 0 until min(wx.hours.size, 6)) {
            val hc = hx + hw * (i + 0.5f)
            c.drawText(hourText[i]!!, hc, r.top + 96 * k, hourPaint)
            Symbols.draw(context, c, Symbols.weather(wx.hourCode[i], wx.hourDay[i]), hc, r.top + 142 * k, 30 * k,
                drawerInk(0.9f))
            c.drawText(hourTempText[i]!!, hc, r.top + 196 * k, hourTemp)
        }
        var sx = hx
        val base = r.top + 258 * k
        if (wx.sunrise != 0L) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k
        if (wx.sunset != 0L) sunItem(c, "sunset", sunsetText, sx, base)
    }

    private fun refreshDrawerText() {
        val wx = data.weather
        val settings = Prefs.version != textPrefs || Ui.language != textLanguage
        textPrefs = Prefs.version
        textLanguage = Ui.language
        if (wx !== textWeather || settings) {
            textWeather = wx
            weatherTitle = if (Prefs.city().isEmpty()) Ui.tr("Clima", "Weather") else Prefs.city()
            nowTemp = HomeData.degrees(wx.temp)
            highLow = when {
                Ui.ru -> "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                Ui.en -> "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                Ui.de -> "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                else -> "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min)
            }
            for (i in 0 until min(wx.hours.size, 6)) {
                hourText[i] = HomeData.hour(wx.hours[i])
                hourTempText[i] = HomeData.degrees(wx.hourTemp[i])
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise)
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset)
        }
        val minute = System.currentTimeMillis() / 60_000
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt
            textMinute = minute
            val stale = HomeData.staleness(data.pricesAt, 5 * 60_000L)
            staleText = if (stale.isEmpty()) Ui.tr("variação em 24 h", "24h change") else Ui.tr("de ", "", "de ") + stale
            for (i in HomeData.COINS.indices) {
                priceText[i] = HomeData.brl(data.prices[i])
                changeText[i] = HomeData.change(data.changes[i])
            }
        }
    }

    private fun sunItem(c: Canvas, symbol: String, label: String, x: Float, base: Float): Float {
        val size = 22 * k
        val sw = Symbols.width(context, symbol, size)
        Symbols.draw(context, c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF.toInt()))
        c.drawText(label, x + sw + 10 * k, base, cardDim)
        return x + sw + 10 * k + cardDim.measureText(label)
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private fun drawQuotesCard(c: Canvas, r: RectF) {
        drawerCard(c, r)
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey()) { cc -> drawPrices(cc, r) }
        else drawPrices(c, r)
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private fun drawerCardsKey(): String =
        "${data.weatherAt}|${data.pricesAt}|${Prefs.version}|${Ui.language}|${dark().toBits()}|${System.currentTimeMillis() / 60_000}"

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private class Snapshot {
        private var bitmap: Bitmap? = null
        private var key = ""

        fun draw(c: Canvas, r: RectF, key: String, content: (Canvas) -> Unit) {
            val left = Math.round(r.left)
            val top = Math.round(r.top)
            val w = max(1, Math.round(r.right) - left)
            val h = max(1, Math.round(r.bottom) - top)
            var b = bitmap
            if (b == null || b.width != w || b.height != h) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = b
                this.key = ""
            }
            if (key != this.key) {
                b.eraseColor(0)
                val cc = Canvas(b)
                cc.translate(-left.toFloat(), -top.toFloat())
                content(cc)
                this.key = key
            }
            c.drawBitmap(b, left.toFloat(), top.toFloat(), null)
        }
    }

    private val weatherSnapshot = Snapshot()
    private val quotesSnapshot = Snapshot()

    private fun drawPrices(c: Canvas, r: RectF) {
        val x = r.left + 34 * k
        val right = r.right - 34 * k
        refreshDrawerText()
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle)
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight)
        val cap = Ui.cap(cardText)
        val coins = HomeData.active()
        for (j in coins.indices) {
            val i = coins[j]
            val base = r.top + (110 + 52 * j) * k
            val mid = base - cap / 2
            val gw = Glyphs.width(context, HomeData.COINS[i], cap)
            Glyphs.coin(context, c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF.toInt()))
            c.drawText(HomeData.coinName(i), x + max(gw, 28 * k) / 2 + 30 * k, base, cardText)
            c.drawText(priceText[i]!!, right - 128 * k, base, pricePaint)
            val change = data.changes[i]
            if (change.isNaN()) continue
            val color = if (change >= 0) tone(0xFF248A3D.toInt(), UP_COLOR) else tone(0xFFD70015.toInt(), DOWN_COLOR)
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k)
            fill.shader = null
            fill.color = Ui.alpha(color, 0.2f)
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill)
            changePaint.color = color
            c.drawText(changeText[i]!!, tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint)
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private fun retarget(snap: Boolean) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        val tilesChanged = aim(tileFocus, if (!sheetOpen && zone == Z_SHELF) shelfIndex else -1, snap)
        var statusChanged = aim(statusFocus, if (!sheetOpen && zone == Z_STATUS) statusIndex else -1, snap)
        val drawerChanged = aim(drawerFocus, if (!sheetOpen && zone == Z_DRAWER) drawerIndex else -1, snap)
        var heroChanged = heroFocus.target != (if (!sheetOpen && zone == Z_HERO) 1f else 0f)
        heroFocus.target = if (!sheetOpen && zone == Z_HERO) 1f else 0f
        if (snap) heroFocus.snap(heroFocus.target)
        val topChanged = aim(topFocus, if (!sheetOpen && zone == Z_TOP) topIndex else -1, snap)
        heroChanged = heroChanged or (aim(cardFocus, if (!sheetOpen && zone == Z_CARDS) cardIndex else -1, snap) or topChanged)
        statusChanged = statusChanged or topChanged
        tilesDirty = tilesDirty or (snap || tilesChanged)
        statusDirty = statusDirty or (snap || statusChanged)
        heroDirty = heroDirty or (snap || heroChanged)
        drawerDirty = drawerDirty or (snap || drawerChanged)
        spoken.focusMoved()
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private fun aim(springs: Array<Spring>, focused: Int, snap: Boolean): Boolean {
        var changed = false
        for (i in springs.indices) {
            val target = if (i == focused) 1f else 0f
            changed = changed or (springs[i].target != target)
            springs[i].target = target
            if (snap) springs[i].snap(target)
        }
        return changed
    }

    // MARK: - "All Apps" sheet

    private fun setOthers(list: List<HomeApps.Item>) {
        others = list
        gridFocus = Array(list.size) { Spring(0.3f, 0.82f, 0f) }
        gridLabels = null
        gridIndex = min(gridIndex, max(0, list.size - 1))
        retargetGrid(true)
        gridDirty = true
    }

    private fun openSheet() {
        sheetOpen = true
        gridIndex = 0
        scroll.snap(0f)
        retargetGrid(true)
        retarget(false)
        sheet.target = 1f
        gridDirty = true
    }

    private fun closeSheet() {
        sheetOpen = false
        sheet.target = 0f
        retarget(false)
    }

    private fun retargetGrid(snap: Boolean) {
        for (i in gridFocus.indices) {
            gridFocus[i].target = if (sheetOpen && i == gridIndex) 1f else 0f
            if (snap) gridFocus[i].snap(gridFocus[i].target)
        }
        val rows = max(1, (others.size + COLUMNS - 1) / COLUMNS)
        val visible = min(rows, VISIBLE_ROWS)
        val r = gridIndex / COLUMNS
        var target = scroll.target
        if (r < target) target = r.toFloat()
        else if (r > target + visible - 1) target = (r - visible + 1).toFloat()
        scroll.target = max(0f, min((rows - visible).toFloat(), target))
        if (snap) scroll.snap(scroll.target)
        spoken.focusMoved()
    }

    private fun drawSheet(c: Canvas) {
        c.drawColor(0xA8000000.toInt())
        val shift = scroll.value * GRID_ROW * k
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle)
        val n = others.size
        var labels = gridLabels
        if (labels == null || labels.size != n) {
            labels = arrayOfNulls<String>(n)
            gridLabels = labels
            for (i in 0 until n) {
                labels[i] = TextUtils.ellipsize(others[i].label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END)
                    .toString()
            }
        }
        val gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP
        val left = (1920 - gridWidth) / 2 * k
        val gridTop = GRID_TOP * k
        for (i in 0 until n) {
            val r = i / COLUMNS
            val col = i % COLUMNS
            val y = gridTop + r * GRID_ROW * k - shift
            if (y > h || y + GRID_ROW * k < 0) continue
            val x = left + col * (GRID_W + GRID_GAP) * k
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k)
            val f = gridFocus[i].value
            drawCard(c, others[i].tile, gridCell, GRID_RADIUS * k, f, 0.08f)
            if (sheetOpen && i == gridIndex) focusedCell.set(cell)
            gridLabel.color = blend(0x99FFFFFF.toInt(), 0xFFFFFFFF.toInt(), Ui.clamp(f))
            c.drawText(labels[i]!!, gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel)
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private fun drawCard(c: Canvas, image: Bitmap?, base: RectF, radius: Float, f: Float, grow: Float) {
        val s = 1 + grow * f
        val cx = base.centerX()
        val cy = base.centerY()
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2)
        val r = radius * s
        val fc = Ui.clamp(f)
        if (f > 0.01f) {
            tmp.set(cell)
            tmp.offset(0f, 16 * k * f)
            shadow.color = Ui.alpha(0xFF000000.toInt(), 0.55f * fc)
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow)
        }
        if (image != null) roundImage(c, image, cell, r)
        if (f > 0.01f) {
            val bh = base.height()
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = LinearGradient(0f, -bh / 2, 0f, 0f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                cardSheenH = bh
            }
            c.save()
            c.translate(cx, cy)
            c.scale(s, s)
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2)
            sheen.shader = cardSheen
            sheen.alpha = Math.round(255 * fc)
            c.drawRoundRect(tmp, radius, radius, sheen)
            sheen.alpha = 255
            c.restore()
        }
        if (fc < 0.99f) {
            veil.color = Ui.alpha(0xFF000000.toInt(), 0.08f * (1 - fc))
            c.drawRoundRect(cell, r, r, veil)
        }
        border.color = 0x14FFFFFF
        c.drawRoundRect(cell, r, r, border)
        if (f > 0.01f) {
            rim.shader = null
            rim.color = Ui.alpha(0xFFFFFFFF.toInt(), 0.4f * fc)
            rim.strokeWidth = 1.5f * k
            c.drawRoundRect(cell, r, r, rim)
            rim.strokeWidth = 2.5f * k
        }
    }

    // MARK: - Toasts

    fun showToast(text: String) {
        toasts.add(text)
        announceForAccessibility(text)
        wake()
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private fun tickToast(up: Long): Boolean {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null
            toastLayer.invalidate()
        }
        if (toast == null && ::toastPaint.isInitialized) {
            val next = toasts.poll()
            if (next != null) {
                toast = layout(next)
                toastAt = up
            }
        }
        if (toast == null) return false
        val age = up - toastAt
        val fading = age < 350 || age > TOAST_MS - 450
        if (fading) toastLayer.invalidate()
        return fading
    }

    private fun drawToast(c: Canvas) {
        val t = toast ?: return
        val age = (SystemClock.uptimeMillis() - toastAt) / 1000f
        val a = min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f))
        if (a <= 0f) return
        val padX = 28 * k
        val padY = 18 * k
        val icon = 26 * k
        val iconW = Symbols.width(context, "bell", icon)
        val tw = t.width + iconW + 16 * k
        val th = t.height.toFloat()
        val toastTop = 64 * k - (1 - a) * 16 * k
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY)
        val save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a))
        scene.glass(c, tmp, min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0f, 0.02f, 1f)
        Symbols.draw(context, c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF.toInt())
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY)
        t.draw(c)
        c.restoreToCount(save)
    }

    private fun layout(text: String): StaticLayout {
        val max = Math.round(720 * k)
        val width = min(max.toDouble(), ceil(Layout.getDesiredWidth(text, toastPaint).toDouble())).toInt()
        return StaticLayout.Builder.obtain(text, 0, text.length, toastPaint, max(1, width))
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private fun checkNotices() {
        val c = context.applicationContext
        LOADER.execute {
            val list = HomeMode.state(c).optJSONArray("notices") ?: return@execute
            val seen = ui.getLong("seenNotice", 0)
            var newest = seen
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            val fresh = ArrayList<String>()
            for (i in list.length() - 1 downTo 0) {   // the list comes newest first
                val o = list.optJSONObject(i) ?: continue
                val at = o.optLong("at")
                if (at <= seen || at < cutoff) continue
                fresh.add(o.optString("text"))
                newest = max(newest, at)
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply()
            if (fresh.isNotEmpty()) handler.post { for (t in fresh) showToast(t) }
        }
    }

    /** XGIMI's Play 6: the battery, read in the background; the corner is redrawn only when it changes. */
    private fun checkBattery() {
        if (!Xgimi.PLAY_6) return
        LOADER.execute {
            val b = Xgimi.battery()
            val text = if (b == null) "" else b.level.toString() + "%"
            val symbol = if (b == null) "" else if (b.adapter && !b.full) "battery_bolt" else if (b.full) "battery_full"
                else if (b.level <= 10) "battery_alert" else "battery_" + max(1, min(6, (b.level * 6 + 99) / 100))
            handler.post {
                if (text == batteryText && symbol == batterySymbol) return@post
                batteryText = text
                batterySymbol = symbol
                statusDirty = true
                wake()
            }
        }
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private fun reload() {
        val c = context.applicationContext
        LOADER.execute {
            val s = HomeApps.shelf(c, true)
            val st = statusList(c)
            val d = drawerList(c, true)
            val o = HomeApps.others(c)
            handler.post {
                setShelf(s)
                setStatusItems(st)
                setDrawerItems(d)
                if (!sheetOpen) setOthers(o)
            }
        }
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    fun onKey(e: KeyEvent): Boolean {
        wake()
        val down = e.action == KeyEvent.ACTION_DOWN
        if (reordering) return reorderKey(e, down)
        val code = e.keyCode
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) move(-1, 0)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) move(1, 0)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) move(0, -1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) move(0, 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.repeatCount == 0) {
                        okDown = true
                        okHeld = false
                        handler.removeCallbacks(holdStart)
                        holdArmed = canReorder()
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS)
                    } else if (okDown && holdArmed && e.isLongPress) {
                        startReorder()
                    }
                } else {
                    holdArmed = false
                    handler.removeCallbacks(holdStart)
                    if (okDown && !okHeld && !e.isCanceled && spoken.keyClick()) activate()
                    okHeld = false
                    okDown = false
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (e.repeatCount == 0) backDown = true
                } else {
                    if (backDown && !e.isCanceled) back()
                    backDown = false
                }
                true
            }
            else -> false
        }
    }

    private fun back() {
        if (sheetOpen) {
            closeSheet()
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer()
            retarget(false)
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF
            retarget(false)
        } else {
            return
        }
        click.move()
    }

    private fun move(dx: Int, dy: Int) {
        if (sheetOpen) {
            moveGrid(dx, dy)
            return
        }
        if (moveLight(dx, dy)) return
        when (zone) {
            Z_SHELF -> {
                if (dx != 0) {
                    val next = max(0, min(shelf.size - 1, shelfIndex + dx))
                    if (next == shelfIndex) return
                    shelfIndex = next
                } else if (dy < 0) {
                    val hr = hero
                    if (hr != null && hr.button) {
                        zone = Z_HERO
                    } else if (statusItems.isNotEmpty()) {
                        zone = Z_STATUS
                        statusIndex = if (shelf.isEmpty()) 0 else nearest(statusRects, tileRects[shelfIndex].centerX())
                    } else {
                        return
                    }
                } else {
                    if (drawerItems.isEmpty()) return
                    openDrawer()
                }
            }
            Z_HERO -> {
                if (dx != 0) {
                    if (!browse(dx)) return
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return
                    zone = Z_STATUS
                    statusIndex = 0
                } else {
                    zone = Z_SHELF
                }
            }
            Z_STATUS -> {
                if (dx != 0) {
                    val next = max(0, min(statusItems.size - 1, statusIndex + dx))
                    if (next == statusIndex) return
                    statusIndex = next
                } else if (dy > 0) {
                    val hr = hero
                    if (hr != null && hr.button) {
                        zone = Z_HERO
                    } else {
                        zone = Z_SHELF
                        if (shelf.isNotEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX())
                    }
                } else {
                    return
                }
            }
            WIDGETS -> {
                val on = widgets()
                val at = on.indexOf(widgetIndex)
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size) {
                    widgetIndex = on[at + dx]
                } else {
                    return
                }
            }
            else -> {
                if (dx != 0) {
                    val next = max(0, min(drawerItems.size - 1, drawerIndex + dx))
                    if (next == drawerIndex) return
                    drawerIndex = next
                } else if (dy < 0) {
                    closeDrawer()
                } else if (talkBack() && widgets().isNotEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS
                    widgetIndex = widgets()[0]
                } else {
                    return
                }
            }
        }
        retarget(false)
        click.move()
    }

    private fun nearest(rects: Array<RectF>, x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in rects.indices) {
            val d = abs(rects[i].centerX() - x)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun moveGrid(dx: Int, dy: Int) {
        val n = others.size
        if (n == 0) return
        val r = gridIndex / COLUMNS
        val col = gridIndex % COLUMNS
        val rows = (n + COLUMNS - 1) / COLUMNS
        var next = gridIndex
        if (dx != 0) {
            val candidate = gridIndex + dx
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate
        } else {
            val targetRow = r + dy
            if (targetRow >= 0 && targetRow < rows) next = min(n - 1, targetRow * COLUMNS + col)
        }
        if (next == gridIndex) return
        gridIndex = next
        retargetGrid(false)
        click.move()
        gridDirty = true
    }

    private fun activate() {
        wake()
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder()
            return
        }
        val item: HomeApps.Item
        val r: RectF
        if (!sheetOpen && zone == WIDGETS) return   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return
            item = others[gridIndex]
            r = focusedCell
        } else if (zone == Z_HERO) {
            val p = currentPick()
            val pkg = p?.pkg
            if (p == null || pkg == null) return
            item = HomeApps.app(pkg, Recs.serviceName(p), p.link)
            r = heroButton
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return
            item = statusItems[statusIndex]
            r = statusRects[statusIndex]
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return
            item = drawerItems[drawerIndex]
            r = drawerRects[drawerIndex]
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex)
                return
            }
            item = lightButtons[topIndex - 3]
            r = topRects[topIndex]
        } else if (zone == Z_CARDS) {
            val p = if (cardIndex < cards.size) cards[cardIndex] else null
            val pkg = p?.pkg
            if (p == null || pkg == null) return
            item = HomeApps.app(pkg, Recs.serviceName(p), p.link)
            r = cardRects[cardIndex]
        } else {
            if (shelf.isEmpty()) return
            item = shelf[shelfIndex]
            r = tileRects[shelfIndex]
        }
        click.open()
        if (item.kind == HomeApps.ALL_APPS) {
            openSheet()
            return
        }
        if (item.kind == HomeApps.SPEAKER) {
            val asked = Xgimi.toggleSpeaker(activity) { on ->
                showToast(when {
                    on == null -> Ui.tr("O modo caixa de som não está disponível agora.", "Speaker mode isn't available right now.")
                    on -> Ui.tr("Modo caixa de som ligado.", "Speaker mode is on.")
                    else -> Ui.tr("Modo caixa de som desligado.", "Speaker mode is off.")
                })
            }
            if (!asked) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + item.label + ".")
            return
        }
        if (item.kind == HomeApps.ECO) {
            Xgimi.toggleEco { on ->
                showToast(when {
                    on == null -> Ui.tr("O modo econômico não está disponível agora.", "Eco mode isn't available right now.")
                    on -> Ui.tr("Modo econômico ligado: menos brilho e som, mais bateria.",
                        "Eco mode is on: less brightness and sound, more battery.")
                    else -> Ui.tr("Modo econômico desligado.", "Eco mode is off.")
                })
            }
            return
        }
        if (item.kind == HomeApps.SLEEP_TIMER) {
            val next = Power.nextTimer(Power.left(Power.timerEnd(activity)), 1)
            Power.setTimer(activity, next)
            showToast(if (next == 0) Ui.tr("Timer para dormir desligado.", "Sleep timer off.")
            else Ui.tr("Timer para dormir: ", "Sleep timer: ") + Power.duration(next) + ".")
            return
        }
        if (item.kind == HomeApps.GLASSES_3D) {
            val to3d = item.key == "glasses-3d"
            val app = activity.applicationContext
            LOADER.execute {
                val s = Rokid.setMode(app, if (to3d) Rokid.MODE_3D else Rokid.MODE_2D)
                handler.post {
                    showToast(when {
                        s == null -> Ui.tr("Não deu para falar com os óculos.", "Couldn't reach the glasses.")
                        s.is3d() != to3d -> Ui.tr("Os óculos não aceitaram essa mudança.", "The glasses didn't take that change.")
                        to3d -> Ui.tr("Óculos em 3D.", "The glasses are in 3D.")
                        else -> Ui.tr("Óculos em 2D.", "The glasses are in 2D.")
                    })
                    reload()   // the button now says the other way
                }
            }
            return
        }
        if (item.kind == HomeApps.INPUTS) {
            openDrawer()
            for (i in drawerItems.indices) {
                if (drawerItems[i].kind == HomeApps.INPUT) {
                    drawerIndex = i
                    break
                }
            }
            retarget(false)
            return
        }
        val opened = HomeApps.open(activity, item, this, Math.round(r.left), Math.round(r.top),
            Math.round(r.width()), Math.round(r.height()))
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + item.label + ".")
    }

    // MARK: - Moving apps on the shelf

    private fun canReorder(): Boolean {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size || shelf.size < 2) return false
        val item = shelf[shelfIndex]
        return item.kind == HomeApps.APP && item.pkg != null
    }

    private fun startReorder() {
        handler.removeCallbacks(holdStart)
        holdArmed = false
        if (reordering || !canReorder()) return
        wake()
        reordering = true
        reorderAnim = true
        okHeld = true
        reorderOrigin = ArrayList(shelf)
        liftIndex = shelfIndex
        reorderAt = SystemClock.uptimeMillis()
        moveLift.target = 1f
        click.open()
        showToast(Ui.tr("← → para mover · OK para soltar · Voltar para cancelar",
            "← → to move · OK to drop · Back to cancel"))
        retarget(false)    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private fun reorderKey(e: KeyEvent, down: Boolean): Boolean {
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) shiftReorder(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.repeatCount == 0) {
                    okHeld = false
                    okDown = false
                    dropReorder()
                } else if (!down) {
                    okHeld = false
                    okDown = false
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (e.repeatCount == 0) backDown = true
                } else {
                    if (backDown && !e.isCanceled) {
                        cancelReorder(false)
                        click.move()
                    }
                    backDown = false
                }
                true
            }
            else -> false
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private fun shiftReorder(dx: Int) {
        val i = shelfIndex
        val j = i + dx
        if (j < 0 || j >= shelf.size) return
        val item = shelf[i]
        shelf[i] = shelf[j]
        shelf[j] = item
        val f = tileFocus[i]
        tileFocus[i] = tileFocus[j]
        tileFocus[j] = f
        val s = tileSlide[i]
        tileSlide[i] = tileSlide[j]
        tileSlide[j] = s
        val d = tileRects[i].left - tileRects[j].left
        tileSlide[j].value += d
        tileSlide[i].value -= d
        if (tileShaders.size == shelf.size) {
            val g = tileShaders[i]
            tileShaders[i] = tileShaders[j]
            tileShaders[j] = g
        }
        shelfIndex = j
        liftIndex = j
        reorderAnim = true
        wake()
        retarget(false)
        click.move()
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private fun dropReorder() {
        if (!reordering) return
        reordering = false
        moveLift.target = 0f
        val origin = reorderOrigin
        var changed = false
        var i = 0
        while (i < shelf.size && !changed) {
            changed = shelf[i] !== origin!![i]
            i++
        }
        reorderOrigin = null
        click.open()
        retarget(false)
        if (!changed) return
        val order = ArrayList<String>()
        for (item in shelf) order.add(item.pkg ?: "")
        val c = context.applicationContext
        LOADER.execute {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            val all = HomeApps.shelfPackages(c)
            var out: MutableList<String> = ArrayList(all)
            var next = 0
            var s = 0
            while (s < out.size && next < order.size) {
                if (order.contains(out[s])) out[s] = order[next++]
                s++
            }
            if (next < order.size) {
                out = ArrayList(order)
                for (p in all) if (!order.contains(p)) out.add(p)
            }
            val save = out
            handler.post { Prefs.setShelf(save) }
        }
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private fun cancelReorder(snap: Boolean) {
        if (!reordering && !reorderAnim) return
        if (reordering) {
            reordering = false
            moveLift.target = 0f
            val n = shelf.size
            val origin = reorderOrigin
            if (origin != null && origin.size == n) {
                val lifted = shelf[shelfIndex]
                val focus = arrayOfNulls<Spring>(n)
                val slide = arrayOfNulls<Spring>(n)
                val shaders = if (tileShaders.size == n) arrayOfNulls<LinearGradient>(n) else tileShaders
                for (a in 0 until n) {
                    val b = shelf.indexOf(origin[a])
                    focus[a] = tileFocus[b]
                    val moved = tileSlide[b]
                    slide[a] = moved
                    moved.value += tileRects[b].left - tileRects[a].left
                    if (shaders !== tileShaders) shaders[a] = tileShaders[b]
                }
                shelf = origin
                // Every slot was filled above (each original item is somewhere on the shelf).
                @Suppress("UNCHECKED_CAST")
                tileFocus = focus as Array<Spring>
                @Suppress("UNCHECKED_CAST")
                tileSlide = slide as Array<Spring>
                tileShaders = shaders
                shelfIndex = shelf.indexOf(lifted)
                liftIndex = shelfIndex
            }
            reorderOrigin = null
            retarget(snap)
        }
        if (snap) {
            moveLift.snap(0f)
            for (s in tileSlide) s.snap(0f)
            wiggle = 0f
            liftIndex = -1
            reorderAnim = false
            tilesDirty = true
        } else {
            wake()
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private fun stepReorder(dt: Float, up: Long): Boolean {
        var moving = step(tileSlide, dt) or moveLift.step(dt)
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS)).toFloat()
            moving = true
        } else {
            wiggle = 0f
        }
        reorderAnim = moving
        return moving
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private fun a11yId(group: Int, index: Int): Int = group * 1000 + index + 1

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = spoken

    private fun a11yItems(out: MutableList<Int>) {
        if (sheetOpen) {
            for (i in 0 until others.size) out.add(a11yId(GRID, i))
            return
        }
        for (i in (if (picksOn()) 0 else 3) until 3 + lightButtons.size) out.add(a11yId(Z_TOP, i))
        for (i in 0 until min(3, cards.size)) out.add(a11yId(Z_CARDS, i))
        for (i in 0 until shelf.size) out.add(a11yId(Z_SHELF, i))
        for (i in 0 until drawerItems.size) out.add(a11yId(Z_DRAWER, i))
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0))
        if (drawerOpen && Prefs.quotes() && HomeData.active().isNotEmpty()) out.add(a11yId(WIDGETS, 1))
    }

    private fun a11yText(id: Int): String {
        val group = (id - 1) / 1000
        val i = (id - 1) % 1000
        return when (group) {
            GRID -> {
                if (i >= others.size) return ""
                label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size
            }
            Z_TOP -> {
                if (i >= 3) return label(lightButtons, i - 3)
                tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3")
            }
            Z_CARDS -> if (i < cards.size) pickText(cards[i]) else ""
            Z_SHELF -> {
                if (reordering && i == shelfIndex) {
                    return Ui.tr("Movendo ", "Moving ", "Moviendo ") + label(shelf, i) +
                        Ui.tr(", posição ", ", position ", ", posición ") + (i + 1) + Ui.tr(" de ", " of ") + shelf.size
                }
                label(shelf, i)
            }
            Z_DRAWER -> label(drawerItems, i)
            WIDGETS -> if (i == 0) weatherText() else quotesText()
            else -> ""
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private fun a11yBounds(id: Int, out: Rect): Boolean {
        val group = (id - 1) / 1000
        val i = (id - 1) % 1000
        val dy: Float
        when (group) {
            GRID -> {
                if (!sheetOpen || i >= others.size) return false
                val gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP
                val x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k
                val y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k)
                dy = 0f
            }
            Z_TOP -> {
                if (i >= 3 + lightButtons.size) return false
                a11yRect.set(topRects[i])
                dy = (if (i < 3) heroLayer else status).translationY
            }
            Z_CARDS -> {
                if (i >= min(3, cards.size)) return false
                a11yRect.set(cardRects[i])
                dy = heroLayer.translationY
            }
            Z_SHELF -> {
                if (i >= tileRects.size) return false
                a11yRect.set(tileRects[i])
                dy = tiles.translationY
            }
            Z_DRAWER -> {
                if (i >= drawerRects.size) return false
                a11yRect.set(drawerRects[i])
                dy = if (drawerOpen) 0f else h.toFloat()
            }
            WIDGETS -> {
                if (!drawerOpen) return false
                a11yRect.set(if (i == 0) weatherCard else quotesCard)
                dy = 0f
            }
            else -> return false
        }
        a11yRect.offset(0f, dy)
        val edge = 24 * k
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h.toFloat())
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0f, a11yRect.right, edge)
        a11yRect.round(out)
        return out.width() > 0 && out.height() > 0
    }

    private fun a11yFocused(): Int {
        if (sheetOpen) return if (others.isEmpty()) -1 else a11yId(GRID, gridIndex)
        return when (zone) {
            Z_TOP -> a11yId(Z_TOP, topIndex)
            Z_CARDS -> if (cardIndex < cards.size) a11yId(Z_CARDS, cardIndex) else -1
            Z_SHELF -> if (shelf.isEmpty()) -1 else a11yId(Z_SHELF, shelfIndex)
            Z_DRAWER -> if (drawerItems.isEmpty()) -1 else a11yId(Z_DRAWER, drawerIndex)
            WIDGETS -> a11yId(WIDGETS, widgetIndex)
            else -> -1
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private fun widgets(): List<Int> {
        val out = ArrayList<Int>()
        if (!drawerOpen) return out
        if (Prefs.weather()) out.add(0)
        if (Prefs.quotes() && HomeData.active().isNotEmpty()) out.add(1)
        return out
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private fun talkBack(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
        return am != null && am.isEnabled &&
            !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty()
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private fun a11yFocus(id: Int) {
        wake()
        val group = (id - 1) / 1000
        val i = (id - 1) % 1000
        if (group == GRID) {
            if (!sheetOpen || i >= others.size) return
            gridIndex = i
            retargetGrid(false)
            gridDirty = true
            click.move()
            return
        }
        if (sheetOpen || reordering) return
        when (group) {
            Z_DRAWER -> {
                if (i >= drawerItems.size) return
                if (!drawerOpen) openDrawer()
                drawerIndex = i
            }
            WIDGETS -> {
                if (!widgets().contains(i)) return
                widgetIndex = i
            }
            Z_TOP -> {
                if (i >= 3 + lightButtons.size || (i < 3 && !picksOn())) return
                if (drawerOpen) closeDrawer()
                topIndex = i
                if (i < 3) selectTab(i)
            }
            Z_CARDS -> {
                if (i >= cards.size) return
                if (drawerOpen) closeDrawer()
                cardIndex = i
            }
            Z_SHELF -> {
                if (i >= shelf.size) return
                if (drawerOpen) closeDrawer()
                shelfIndex = i
            }
            else -> return
        }
        zone = group
        retarget(false)
        click.move()
    }

    private fun label(list: List<HomeApps.Item>, i: Int): String = if (i >= 0 && i < list.size) list[i].label ?: "" else ""

    /** A recommendation: title, details and the app that plays it. */
    private fun pickText(p: Recs.Pick?): String {
        if (p == null) return ""
        val s = StringBuilder(p.title ?: "")
        val meta = p.meta
        if (meta != null && meta.isNotEmpty()) s.append(", ").append(meta)
        val app = Recs.serviceName(p)
        if (app != null && app.isNotEmpty()) s.append(". App: ").append(app)
        return s.toString()
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private fun weatherText(): String {
        val s = StringBuilder(if (Prefs.city().isEmpty()) Ui.tr("Clima", "Weather") else Prefs.city())
        val wx = data.weather
        if (wx.temp.isNaN()) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString()
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
            .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
            .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.')
        val hours = min(6, wx.hours.size)
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "))
        for (i in 0 until hours) {
            s.append(HomeData.hour(wx.hours[i])).append(' ').append(HomeData.degrees(wx.hourTemp[i]))
                .append(if (i < hours - 1) ", " else ".")
        }
        if (wx.sunrise != 0L) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.')
        if (wx.sunset != 0L) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.')
        return s.toString()
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private fun quotesText(): String {
        val s = StringBuilder(Ui.tr("Cotações", "Crypto"))
        val stale = HomeData.staleness(data.pricesAt, 5 * 60_000L)
        s.append(", ").append(if (stale.isEmpty()) Ui.tr("variação em 24 h", "24h change") else Ui.tr("de ", "", "de ") + stale)
            .append('.')
        for (i in HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]))
            if (!data.changes[i].isNaN()) s.append(", ").append(HomeData.change(data.changes[i]))
            s.append('.')
        }
        return s.toString()
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private fun layoutLight() {
        if (!::lTab.isInitialized) return
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        val pad = 26 * k
        val tabTop = L_TABBAR_TOP * k
        val tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k
        var x = MARGIN * k - pad
        for (i in 0 until 3) {
            val tw = max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]))
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom)
            x = topRects[i].right + 4 * k
        }
        val b = L_BUTTON * k
        var right = CONTENT_R * k
        val cy = L_BUTTON_CY * k
        for (i in lightButtons.size - 1 downTo 0) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2)
            right -= b + L_BUTTON_GAP * k
        }
        val top = L_CARDS_TOP * k
        val ch = L_CARD_H * k
        val g = L_CARD_GAP * k
        val left = MARGIN * k
        val tall = L_TALL_W * k
        val wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g
        cardRects[0].set(left, top, left + wide, top + ch)
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch)
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch)
        heroDirty = true
        statusDirty = true
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private fun updateCards() {
        val list = recs.now(TAB_KIND[tab])
        if (!samePicks(list, cards)) {
            cards = list
            cardIndex = min(cardIndex, max(0, list.size - 1))
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF
                retarget(false)
            }
        }
        for (p in recs.wanted()) recs.backdrop(p)   // all three tabs preloaded: switching tabs is instant
        val b = if (cards.isEmpty()) null else recs.backdrop(cards[0])
        if (cards.isEmpty() || b != null) showBackdrop(b)
        heroDirty = true
        spoken.focusMoved()    // new picks under the focused card
    }

    private fun selectTab(index: Int) {
        if (index == tab) return
        tab = index
        updateCards()
        heroDirty = true
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private fun drawLightSky(c: Canvas) {
        val up = SystemClock.uptimeMillis()
        val e = if (bgFadeAt == 0L) 1f else fadeEase(bgFadeAt, up)
        val fading = bgFadeAt != 0L
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        val v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), night))
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) {
                lightGradient = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFF5F5F7.toInt(), 0xFFE2E2E7.toInt(), Shader.TileMode.CLAMP)
            }
            if (nightGradient == null) {
                nightGradient = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1C1C1E.toInt(), 0xFF0B0B0D.toInt(), Shader.TileMode.CLAMP)
            }
            fill.colorFilter = v
            fill.color = 0xFF000000.toInt()
            if (night < 0.999f) {
                fill.shader = lightGradient
                c.drawRect(screen, fill)
            }
            if (night > 0.001f) {
                fill.shader = nightGradient
                fill.alpha = Math.round(255 * night)
                c.drawRect(screen, fill)
                fill.alpha = 255
            }
            fill.shader = null
            fill.colorFilter = null
        }
        imagePaint.colorFilter = v
        if (bgTo == null || (fading && bgFrom == null)) {
            drawImage(c, skyLight, 1f)
            drawImage(c, skyDark, night)
        }
        val from = bgFrom
        if (fading && from != null) drawLightImage(c, from, if (bgTo == null) 1 - e else 1f)
        val to = bgTo
        if (to != null) drawLightImage(c, to, if (fading) e else 1f)
        imagePaint.colorFilter = null
    }

    private fun loadSky(c: Context) {
        LOADER.execute {
            // light-1.jpg + dark-1.jpg, light-2.jpg + dark-2.jpg...: one pair a day, in turn; else light.jpg and dark.jpg.
            var pairs = 0
            while (hasSky(c, "light-" + (pairs + 1)) && hasSky(c, "dark-" + (pairs + 1))) pairs++
            val n = if (pairs == 0) "" else "-" + (1 + ((System.currentTimeMillis() / 86_400_000L) % pairs).toInt())
            val light = sky(c, "light$n")
            val dark = sky(c, "dark$n")
            handler.post {
                skyLight = light
                skyDark = dark
                invalidate()
            }
        }
    }

    private fun hasSky(c: Context, name: String): Boolean = try {
        c.assets.open("backgrounds/$name.jpg").use { true }
    } catch (e: IOException) {
        false
    }

    private fun sky(c: Context, name: String): Bitmap? = try {
        c.assets.open("backgrounds/$name.jpg").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {   // IOException or RuntimeException (nothing else is thrown here)
        AppLog.w("background " + name + ": " + e.message)
        null
    }

    private fun drawLightImage(c: Canvas, b: Backdrop, alpha: Float) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha)
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night)
    }

    private fun drawLightTop(c: Canvas) {
        applyTone()
        if (!picksOn()) {
            drawClock(c)
            if (HomeData.coinCount() > 0) drawQuotes(c)
            return
        }
        val hello = Ui.tr("Para você", "For You")
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle)
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        val focus = Ui.clamp(topFocus[tab].value)
        for (i in 0 until 3) {
            val r = topRects[i]
            if (i == tab && focus > 0.01f) {
                val s = 1 + 0.05f * focus
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                    r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2)
                val rr = cell.height() / 2
                if (focus > 0.01f) {
                    tmp.set(cell)
                    tmp.offset(0f, 8 * k * focus)
                    shadow.color = Ui.alpha(0xFF000000.toInt(), tone(0.22f, 0.4f) * focus)
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow)
                }
                fill.shader = null
                fill.color = Ui.alpha(0xFFFFFFFF.toInt(), focus)
                c.drawRoundRect(cell, rr, rr, fill)
            }
            val p = if (i == tab) lTabOn else lTab
            if (i == tab) lTabOn.color = blend(tone(L_INK, WHITE), L_INK, focus)   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p)
        }
        var top = -1
        for (i in 0 until 3) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i
        for (i in 0 until 3) if (i != top) drawLightCard(c, i)
        if (top >= 0) drawLightCard(c, top)
        if (HomeData.coinCount() > 0) drawQuotes(c)
    }

    private fun picksOn(): Boolean = Recs.hasKey(context)

    private fun drawClock(c: Canvas) {
        val x = MARGIN * k
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate)
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock)
        val wx = data.weather
        if (!Prefs.weather() || wx.temp.isNaN()) return
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || Ui.language != weatherLineLanguage) {
            weatherLineAt = data.weatherAt
            weatherLinePrefs = Prefs.version
            weatherLineLanguage = Ui.language
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   " + when {
                Ui.ru -> "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                Ui.en -> "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                Ui.de -> "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                else -> "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min)
            }
        }
        val size = 40 * k
        val base = L_WEATHER_BASE * k
        val symbol = Symbols.weather(wx.code, wx.isDay)
        val sw = Symbols.width(context, symbol, size)
        Symbols.draw(context, c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.color)
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather)
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private fun drawLightCard(c: Canvas, i: Int) {
        val base = cardRects[i]
        val p = if (i < cards.size) cards[i] else null
        val v = cardFocus[i].value
        val f = Ui.clamp(v)
        val s = 1 + 0.05f * v
        val bw = base.width()
        val bh = base.height()
        val r0 = 18 * k
        val cx = base.centerX()
        val cy = base.centerY() - 6 * k * v
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2)
        val r = r0 * s
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2)
        tmp.offset(0f, (10 + 14 * f) * k)
        shadowDeep.color = Ui.alpha(0xFF000000.toInt(), tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f)
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep)
        c.restore()
        val b = if (p == null) null else recs.backdrop(p)
        val img = if (b == null) null else if (i == 0) b.wide else b.tall
        if (img != null) {
            roundImage(c, img, cell, r)
        } else {
            fill.shader = null
            fill.color = tone(0xFFDADADF.toInt(), 0xFF2C2C2E.toInt())
            c.drawRoundRect(cell, r, r, fill)
        }
        if (f > 0.01f) {
            if (lightSheens.size != cardRects.size) {
                lightSheens = arrayOfNulls(cardRects.size)
                lightRims = arrayOfNulls(cardRects.size)
                lightShaderW = FloatArray(cardRects.size)
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = LinearGradient(-bw / 2, -bh / 2, 0f, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                lightRims[i] = LinearGradient(0f, -bh / 2, 0f, bh / 2, 0xE6FFFFFF.toInt(), 0x59FFFFFF, Shader.TileMode.CLAMP)
                lightShaderW[i] = bw
            }
            val alpha = Math.round(255 * f)
            c.save()
            c.translate(cx, cy)
            c.scale(s, s)
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2)
            sheen.shader = lightSheens[i]
            sheen.alpha = alpha
            c.drawRoundRect(tmp, r0, r0, sheen)
            sheen.alpha = 255
            rim.shader = lightRims[i]
            rim.alpha = alpha
            rim.strokeWidth = 2.5f * k / s
            c.drawRoundRect(tmp, r0, r0, rim)
            rim.strokeWidth = 2.5f * k
            rim.alpha = 255
            c.restore()
        }
        if (p == null) return
        fitPaint.set(lCardTitle)
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left,
            base.bottom + 46 * k, lCardTitle)
        val meta = p.meta
        if (i == 0 && meta != null && meta.isNotEmpty()) {
            fitPaint.set(lCardMeta)
            c.drawText(TextUtils.ellipsize(meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left,
                base.bottom + 80 * k, lCardMeta)
        }
    }

    /**
     * Live TV (where there is one), HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it. On XGIMI's Play 6 and the Rokid Station,
     * the battery to their left.
     */
    private fun drawLightButtons(c: Canvas) {
        applyTone()
        if (batteryText.isNotEmpty() && lightButtons.isNotEmpty()) {
            // The battery, left of the round buttons: its level, then its icon (red when it's nearly empty).
            val size = 40 * k
            val iw = Symbols.width(context, batterySymbol, size)
            val right = topRects[3].left - 30 * k
            val cy = L_BUTTON_CY * k
            val ink = tone(L_INK, 0xEBFFFFFF.toInt())
            Symbols.draw(context, c, batterySymbol, right - iw / 2, cy, size,
                if (batterySymbol == "battery_alert") 0xFFFF453A.toInt() else ink)
            lBattery.color = ink
            c.drawText(batteryText, right - iw - 10 * k - lBattery.measureText(batteryText), cy + Ui.halfCap(lBattery), lBattery)
        }
        drawBattery(c)
        for (i in 0 until lightButtons.size) {
            val r = topRects[3 + i]
            val f = Ui.clamp(topFocus[3 + i].value)
            val s = 1 + 0.1f * f
            val rad = r.width() / 2 * s
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad)
            tmp.set(cell)
            tmp.offset(0f, (6 + 8 * f) * k)
            shadow.color = Ui.alpha(0xFF000000.toInt(), tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f)
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow)
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF.toInt(), SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                tone(0.6f, 0.45f), 1 - f)
            if (f > 0.01f) {
                fill.shader = null
                fill.color = Ui.alpha(0xFFFFFFFF.toInt(), f)
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill)
            }
            Symbols.draw(context, c, lightButtons[i].symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                blend(tone(L_INK, 0xEBFFFFFF.toInt()), L_INK, f))
            if (f > 0.01f) {
                val label = lightButtons[i].label ?: ""
                val half = lTip.measureText(label) / 2
                val x = min(r.centerX(), CONTENT_R * k - half)
                lTip.alpha = Math.round(255 * f)
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip)
            }
        }
    }

    /** Rokid Station: the battery, left of the corner buttons (its level, and a bolt while plugged in). */
    private fun drawBattery(c: Canvas) {
        val b = battery
        if (b == null || lightButtons.isEmpty()) return
        val text = b.level.toString() + "%"
        val right = topRects[3].left - 28 * k
        val cy = L_BUTTON_CY * k
        val ink = tone(L_INK, 0xEBFFFFFF.toInt())
        batteryPaint.color = ink
        val tw = batteryPaint.measureText(text)
        c.drawText(text, right - tw, cy + Ui.halfCap(batteryPaint), batteryPaint)
        val symbol = Power.symbol(b)
        val sw = Symbols.width(context, symbol, 26 * k)
        Symbols.draw(context, c, symbol, right - tw - 10 * k - sw / 2, cy, 26 * k, ink)
    }

    private fun moveLight(dx: Int, dy: Int): Boolean {
        when (zone) {
            Z_SHELF -> {
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false
                if (cards.isEmpty()) {
                    zone = Z_TOP
                    topIndex = if (picksOn()) tab else 3
                } else {
                    zone = Z_CARDS
                    cardIndex = nearest(cardRects, cards.size, if (shelf.isEmpty()) w / 2f else tileRects[shelfIndex].centerX())
                }
            }
            Z_CARDS -> {
                if (dx != 0) {
                    val next = max(0, min(cards.size - 1, cardIndex + dx))
                    if (next == cardIndex) return true
                    cardIndex = next
                } else if (dy < 0) {
                    zone = Z_TOP
                    topIndex = tab     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF
                    if (shelf.isNotEmpty()) shelfIndex = nearest(tileRects, tileRects.size, cardRects[cardIndex].centerX())
                }
            }
            Z_TOP -> {
                if (dx != 0) {
                    val next = max(if (picksOn()) 0 else 3, min(2 + lightButtons.size, topIndex + dx))
                    if (next == topIndex) return true
                    topIndex = next
                    if (topIndex < 3) selectTab(topIndex)   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF
                    } else {
                        zone = Z_CARDS
                        cardIndex = nearest(cardRects, cards.size, topRects[topIndex].centerX())
                    }
                } else {
                    return true
                }
            }
            else -> return false
        }
        retarget(false)
        click.move()
        return true
    }

    private fun nearest(rects: Array<RectF>, count: Int, x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until min(count, rects.size)) {
            val d = abs(rects[i].centerX() - x)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    // MARK: - Helpers

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = Ui.clamp((x - e0) / (e1 - e0))
        return t * t * (3 - 2 * t)
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
}
