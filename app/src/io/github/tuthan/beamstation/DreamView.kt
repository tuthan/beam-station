package io.github.tuthan.beamstation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the compact prices;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora, wandering slowly.
 */
internal class DreamView(c: Context) : View(c), Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private class Slide(
        val pick: Recs.Pick, val image: Bitmap, val frost: Bitmap, val lightCaption: Float, val lightClock: Float,
        n: Int
    ) {
        val shader: BitmapShader    // the frosted version, for the clock's glass
        val zoomIn: Boolean
        val panX: Float
        val panY: Float
        val caption = Recording.create("caption")
        var startedAt = 0L

        init {
            shader = BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR)
            zoomIn = n % 2 == 0
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = if ((n / 2) % 2 == 0) 0.8f else -0.8f
            panY = if (n % 3 == 0) -0.6f else 0.6f
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private class Glass(
        val text: String, val slope: Bitmap, val cover: Bitmap, val shadow: Bitmap, val x: Int, val y: Int,
        val inkTop: Float, val inkHeight: Float
    )

    private val scene = Scene(false)
    private val data: HomeData
    private val recs: Recs
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val content = Recording.create("clock")     // without photos: the clock over the aurora
    private val chrome = Recording.create("corner")     // date, weather, prices and the clock's shadow
    private val timeOffset = (Math.random() * 300).toFloat()
    private val matrix = Matrix()
    private val placeA = FloatArray(3)
    private val placeB = FloatArray(3)
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val black = Paint()
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glassPaint = Paint()
    private val failed = HashSet<String>()     // Recs.key: a movie and a series can have the same ID
    private var glassShader: RuntimeShader? = null
    private var running = false
    private var dirty = true
    private var chromeDirty = true
    private var fallback = false       // the aurora with the clock (no photos)
    private var decoding = false
    private var generation = 0
    private var slides = 0
    private var current: Slide? = null
    private var incoming: Slide? = null
    private var prepared: Slide? = null
    private var glass: Glass? = null
    private var glassFor = ""
    private var startedAt = 0L
    private var firstAt = 0L
    private var lastFrame = 0L
    private var lastSky = 0L
    private var w = 0
    private var h = 0
    private var driftX = 0
    private var driftY = 0     // the corner's and the caption's offset this frame, in whole pixels
    private var driftPhase = 0.0      // where on the path this dream started
    private var k = 1f
    private var minute = -1L
    private var clock = ""
    private var date = ""
    private lateinit var datePaint: Paint
    private lateinit var clockPaint: Paint
    private lateinit var weatherPaint: Paint
    private lateinit var weatherDim: Paint
    private lateinit var quotePaint: Paint
    private lateinit var stalePaint: Paint
    private lateinit var cornerDate: Paint
    private lateinit var cornerQuote: Paint
    private lateinit var cornerStale: Paint
    private lateinit var cornerClock: Paint
    private lateinit var labelPaint: Paint
    private lateinit var metaPaint: Paint
    private lateinit var titlePaint: TextPaint

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            data.refreshIfStale()
            recs.refreshIfStale()
            recs.prefetchAll()
            dirty = true
            chromeDirty = true
            prepareNext()
            handler.postDelayed(this, 60_000)
        }
    }

    init {
        data = HomeData.get(c)
        recs = Recs.get(c)
        Shaders.dream(scene.palette)
        scene.setMotion(0.4f, 0.32f)
        scene.applyPalette()
        content.setUseCompositingLayer(true, null)
        try {
            glassShader = RuntimeShader(Shaders.GLASS_TEXT)
            glassPaint.shader = glassShader
        } catch (e: RuntimeException) {   // Android 9 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.message)
            glassShader = null
        } catch (e: LinkageError) {
            AppLog.w("glass clock unavailable: " + e.message)
            glassShader = null
        }
        black.color = 0xFF000000.toInt()
        shadowPaint.color = 0xFF000000.toInt()
        // Lines of text that never overlap: the dim's alpha goes per primitive, not through a full-screen layer a frame.
        chrome.setHasOverlappingRendering(false)
    }

    fun start() {
        if (running) return
        running = true
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        startedAt = clock()
        driftPhase = Math.random() * 2 * PI
        lastFrame = 0
        failed.clear()
        fallback = recs.showcase().isEmpty()
        data.addListener(this)
        recs.addListener(this)
        data.refreshIfStale()
        recs.refreshIfStale()
        recs.prefetchAll()
        prepareNext()
        handler.postDelayed(tick, 60_000)
        Choreographer.getInstance().postFrameCallback(this)
        invalidate()
    }

    fun stop() {
        if (!running) return
        running = false
        generation++
        decoding = false
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacks(tick)
        data.removeListener(this)
        recs.removeListener(this)
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        val gone = HashSet<Bitmap>()
        for (s in arrayOf(current, incoming, prepared)) {
            if (s == null) continue
            gone.add(s.image)
            gone.add(s.frost)
        }
        if (gone.isNotEmpty()) handler.postDelayed({ for (b in gone) b.recycle() }, 1000)
        current = null
        incoming = null
        prepared = null
        glass = null
        slides = 0
        glassFor = ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        worker.shutdownNow()
    }

    override fun onData() {
        dirty = true
        chromeDirty = true
        invalidate()
    }

    override fun onRecs() {
        if (running) prepareNext()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        w = width
        h = height
        k = w / 1920f
        scene.setSize(w, h)
        content.setPosition(0, 0, w, h)
        chrome.setPosition(0, 0, w, h)
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF.toInt())
        datePaint.textAlign = Paint.Align.CENTER
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF.toInt())
        clockPaint.textAlign = Paint.Align.CENTER
        clockPaint.letterSpacing = -0.01f
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF.toInt())
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF.toInt())
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF.toInt())
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF)
        stalePaint.textAlign = Paint.Align.CENTER
        // With photos: the corner (date, clock, prices) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF.toInt())
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF.toInt())
        cornerStale = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF.toInt())
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF.toInt())
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF.toInt())
        labelPaint.letterSpacing = 0.1f
        titlePaint = TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF.toInt()))
        titlePaint.letterSpacing = 0.005f
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF.toInt())
        metaPaint.letterSpacing = 0.014f
        for (p in arrayOf(cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint)) {
            p.setShadowLayer(18 * k, 0f, 2 * k, 0x66000000)
        }
        glassFor = ""
        dirty = true
        chromeDirty = true
        for (s in arrayOf(current, incoming, prepared)) if (s != null) recordCaption(s)
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private fun prepareNext() {
        if (!running || decoding || prepared != null) return
        val list = recs.showcase()
        if (list.isEmpty()) return
        val last = incoming ?: current
        var start = 0
        if (last != null) {
            for (i in list.indices) {
                if (Recs.key(list[i]) == Recs.key(last.pick)) {
                    start = i + 1
                    break
                }
            }
        }
        var pick: Recs.Pick? = null
        var i = 0
        while (i < list.size && pick == null) {
            val p = list[(start + i) % list.size]
            if (!failed.contains(Recs.key(p))) pick = p
            i++
        }
        if (pick == null) {
            if (current == null) fallback = true
            return
        }
        if (last != null && Recs.key(pick) == Recs.key(last.pick)) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock)
            return
        }
        val file = recs.readyImage(pick)
        if (file == null) {
            failed.add(Recs.key(pick))   // the image is gone from disk: try the next one
            prepareNext()
            return
        }
        decoding = true
        val gen = generation
        val chosen: Recs.Pick = pick
        worker.execute {
            var images: Array<Bitmap>? = null
            val light = FloatArray(2)
            try {
                images = Backdrop.photo(file, PW, PH, light)
                images[0] = Backdrop.gpu(withScrims(images[0], light[0], light[1]))
            } catch (e: Exception) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.message)
            } catch (e: OutOfMemoryError) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.message)
            }
            val ready = images
            handler.post {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (b in ready) b.recycle()
                    return@post
                }
                decoding = false
                if (ready == null) {
                    failed.add(Recs.key(chosen))
                    prepareNext()
                    return@post
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1])
                invalidate()
            }
        }
    }

    private fun newSlide(pick: Recs.Pick, image: Bitmap, frost: Bitmap, lightCaption: Float, lightClock: Float): Slide {
        val s = Slide(pick, image, frost, lightCaption, lightClock, slides++)
        if (w > 0) recordCaption(s)
        return s
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private fun advance(up: Long) {
        val cur = current
        if (cur == null) {
            val next = prepared ?: return
            current = next
            prepared = null
            next.startedAt = up
            firstAt = up
            bindPhotos()
            prepareNext()
            return
        }
        val next = prepared
        if (incoming == null && next != null && up >= cur.startedAt + SHOW_MS) {
            incoming = next
            prepared = null
            next.startedAt = up
            bindPhotos()
            return
        }
        val inc = incoming
        if (inc != null && up >= inc.startedAt + FADE_MS) {
            val old = cur
            current = inc
            incoming = null
            bindPhotos()
            if (old.image !== inc.image) {
                // Once the last frame that uses them has been drawn.
                val gone = old.image
                val goneFrost = old.frost
                handler.postDelayed({
                    gone.recycle()
                    goneFrost.recycle()
                }, 1000)
            }
            prepareNext()
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private fun bindPhotos() {
        val gs = glassShader
        val cur = current
        if (gs == null || cur == null) return
        gs.setInputShader("imageA", cur.shader)
        gs.setInputShader("imageB", incoming?.shader ?: cur.shader)
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private fun place(s: Slide, up: Long, out: FloatArray) {
        val u = Ui.clamp((up - s.startedAt) / (SHOW_MS + FADE_MS).toFloat())
        val f = if (s.zoomIn) u else 1 - u
        val z = 1 + ZOOM * f
        val rx = w * (z - 1) / 2
        val ry = h * (z - 1) / 2
        out[0] = rx * (s.panX * f - 1)
        out[1] = ry * (s.panY * f - 1)
        out[2] = w.toFloat() / s.image.width * z
    }

    private fun drawPhoto(c: Canvas, s: Slide, place: FloatArray, alpha: Float) {
        if (alpha <= 0.004f) return
        matrix.setScale(place[2], place[2])
        matrix.postTranslate(place[0], place[1])
        photoPaint.alpha = Math.round(255 * min(1f, alpha))
        c.drawBitmap(s.image, matrix, photoPaint)
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** The clock that drives the animations. */
    private fun clock(): Long {
        val up = SystemClock.uptimeMillis()
        return up
    }

    override fun onDraw(canvas: Canvas) {
        if (w == 0) return
        val up = clock()
        val now = System.currentTimeMillis()
        if (now / 60_000 != minute) {
            minute = now / 60_000
            clock = Ui.clock(now)
            date = Ui.date(now)
            dirty = true
            chromeDirty = true
        }
        if (running) advance(up)
        if (glassFor != clock) buildGlass(clock)

        val first = if (current == null) 0f else fadeIn(up - firstAt, FIRST_MS)
        if (current == null || first < 1f) {
            if (fallback) drawFallback(canvas, up, if (current == null) 1f else 1 - first)
            else canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), black)
        }
        val current = current ?: return
        if (fallback && first >= 1f) fallback = false

        // The dim eases in over a few seconds, ten minutes after start(). The photos dim over black: the incoming one
        // at e·d and the current one at d(1 - e)/(1 - d·e) add up to d times the plain crossfade, with no jump at its end.
        val dim = fadeIn(up - startedAt - DIM_AFTER_MS, DIM_MS)
        val d = 1 - (1 - PHOTO_DIM) * dim
        val corner = 1 - (1 - CHROME_DIM) * dim
        if (d < 1f && first >= 1f) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), black)
        place(current, up, placeA)
        val incoming = incoming
        val e = if (incoming == null) 0f else fadeIn(up - incoming.startedAt, FADE_MS)
        drawPhoto(canvas, current, placeA, first * (if (incoming == null || d >= 1f) d else d * (1 - e) / (1 - d * e)))
        if (incoming != null) {
            place(incoming, up, placeB)
            drawPhoto(canvas, incoming, placeB, e * d)
        }

        if (chromeDirty) recordChrome()
        drift(up)
        chrome.setAlpha(first * corner)
        chrome.setTranslationX(driftX.toFloat())
        chrome.setTranslationY(driftY.toFloat())
        chrome.draw(canvas)
        drawGlass(canvas, first * corner, e)
        drawCaptions(canvas, up, corner)
    }

    /** The fallback's slow Lissajous path, from this dream's own point on it, rounded so the digits stay sharp. */
    private fun drift(up: Long) {
        val phase = 2 * PI * (up - startedAt) / 1000 / DRIFT_PERIOD_S + driftPhase
        driftX = Math.round((DRIFT_X * k * sin(phase)).toFloat())
        driftY = Math.round((DRIFT_Y * k * sin(2 * phase + 0.7)).toFloat())
    }

    // MARK: - Corner: date and weather, glass clock, prices

    private fun recordChrome() {
        val c = chrome.beginRecording(w, h)
        try {
            val right = RIGHT * k
            var x = right
            val wx = data.weather
            if (!wx.temp.isNaN() && Prefs.weather()) {
                val temp = HomeData.degrees(wx.temp)
                val tw = cornerDate.measureText(temp)
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate)
                x -= tw + 10 * k
                val symbol = Symbols.weather(wx.code, wx.isDay)
                val size = 30 * k
                val sw = Symbols.width(context, symbol, size)
                Symbols.draw(context, c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size,
                    0xEBFFFFFF.toInt())
                x -= sw + 26 * k
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate)

            val g = glass
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.alpha = 140
                c.drawBitmap(g.shadow, g.x.toFloat(), g.y + 6 * k, shadowPaint)
            } else {
                val ink = Rect()
                cornerClock.getTextBounds(clock, 0, clock.length, ink)
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock)
            }

            // Compact prices under the clock, right-aligned.
            val base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k
            val cap = Ui.cap(cornerQuote)
            val gap = 7 * k
            val spacing = 26 * k
            val coins = HomeData.active()
            val n = HomeData.coinCount()
            val values = arrayOfNulls<String>(n)
            val glyphs = FloatArray(n)
            var total = spacing * (n - 1)
            for (i in 0 until n) {
                values[i] = HomeData.compact(data.prices[coins[i]])
                glyphs[i] = Glyphs.width(context, HomeData.COINS[coins[i]], cap)
                total += glyphs[i] + gap + cornerQuote.measureText(values[i])
            }
            var qx = right - total
            for (i in 0 until n) {
                Glyphs.coin(context, c, HomeData.COINS[coins[i]], qx + glyphs[i] / 2, base - cap / 2, cap,
                    0xA6FFFFFF.toInt())
                qx += glyphs[i] + gap
                c.drawText(values[i]!!, qx, base, cornerQuote)
                qx += cornerQuote.measureText(values[i]) + spacing
            }
            val stale = HomeData.staleness(data.pricesAt, 5 * 60_000L)
            if (!stale.isEmpty() && n > 0) {
                c.drawText(stale, right - total - 24 * k - cornerStale.measureText(stale), base, cornerStale)
            }
        } finally {
            chrome.endRecording()
        }
        chromeDirty = false
    }

    private fun drawGlass(c: Canvas, alpha: Float, e: Float) {
        val g = glass
        val gs = glassShader
        if (g == null || gs == null || alpha <= 0.004f) return
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        val f = Backdrop.PHOTO_FROST.toFloat()
        gs.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f)
        if (incoming != null) gs.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f)
        else gs.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f)
        gs.setFloatUniform("mixB", if (incoming == null) 0f else e)
        val x = g.x + driftX
        val y = g.y + driftY    // drifts with its shadow, in the corner
        gs.setFloatUniform("origin", x.toFloat(), y.toFloat())
        gs.setFloatUniform("ink", g.inkTop, g.inkHeight)
        gs.setFloatUniform("refraction", 14 * k)
        gs.setFloatUniform("specular", 1f)
        gs.setFloatUniform("alpha", alpha)
        c.drawRect(x.toFloat(), y.toFloat(), (x + g.cover.width).toFloat(), (y + g.cover.height).toFloat(), glassPaint)
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private fun buildGlass(text: String) {
        glassFor = text
        val gs = glassShader
        if (gs == null || text.isEmpty()) {
            chromeDirty = true
            return
        }
        val paint = Paint(cornerClock)
        paint.clearShadowLayer()
        paint.color = 0xFFFFFFFF.toInt()
        val right = RIGHT * k
        val top = CLOCK_TOP * k
        val scale = k
        val gen = generation
        worker.execute {
            val g = makeGlass(text, paint, right, top, scale)
            handler.post {
                if (gen != generation || text != glassFor) return@post
                glass = g
                gs.setInputShader("slope", nearest(g.slope))
                gs.setInputShader("cover", nearest(g.cover))
                chromeDirty = true
                invalidate()
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private fun recordCaption(s: Slide) {
        val c = s.caption.beginRecording(w, h)
        try {
            val x = MARGIN * k
            val metaBase = META_BASE * k
            val titleBase = metaBase - 58 * k
            val title = s.pick.title ?: ""
            val t = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, Math.round(TITLE_W * k))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).build()
            val originY = titleBase - t.getLineBaseline(t.lineCount - 1)
            val capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint)
            c.drawText(Recs.periodLabel(), x, capTop - 30 * k, labelPaint)
            c.save()
            c.translate(x - Ui.leftBearing(titlePaint, title), originY)
            t.draw(c)
            c.restore()
            val meta = s.pick.meta
            if (meta != null && !meta.isEmpty()) c.drawText(meta, x, metaBase, metaPaint)
        } finally {
            s.caption.endRecording()
        }
        s.caption.setPosition(0, 0, w, h)
        s.caption.setHasOverlappingRendering(false)   // text lines only: the fades and the dim need no layer
    }

    /**
     * The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). Both
     * drift and dim with the corner.
     */
    private fun drawCaptions(c: Canvas, up: Long, dim: Float) {
        val current = current!!
        val incoming = incoming
        if (incoming == null) {
            val a = if (current.startedAt == firstAt) fadeIn(up - firstAt - 700, 900) else 1f
            current.caption.setAlpha(a * dim)
            current.caption.setTranslationX(driftX.toFloat())
            current.caption.setTranslationY(driftY + 14 * k * (1 - a))
            current.caption.draw(c)
            return
        }
        val t = up - incoming.startedAt
        val out = 1 - Ui.clamp(t / 500f)
        if (out > 0.004f) {
            current.caption.setAlpha(out * dim)
            current.caption.setTranslationX(driftX.toFloat())
            current.caption.setTranslationY(driftY.toFloat())
            current.caption.draw(c)
        }
        val enter = fadeIn(t - 1300, 900)
        if (enter > 0.004f) {
            incoming.caption.setAlpha(enter * dim)
            incoming.caption.setTranslationX(driftX.toFloat())
            incoming.caption.setTranslationY(driftY + 14 * k * (1 - enter))
            incoming.caption.draw(c)
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private fun drawFallback(canvas: Canvas, up: Long, alpha: Float) {
        val t = (up - startedAt) / 1000f
        if (up - lastSky >= 100) {
            lastSky = up
            scene.advance(timeOffset + t)
        }
        scene.drawBackground(canvas)
        if (dirty) {
            val rc = content.beginRecording(w, h)
            try {
                drawContent(rc)
            } finally {
                content.endRecording()
            }
            dirty = false
        }
        val across = 2 * PI * t / DRIFT_PERIOD_S + driftPhase
        val rise = 2 * PI * t / WANDER_PERIOD_Y_S + 2 * driftPhase
        content.setTranslationX(Math.round((WANDER_X * k * sin(across)).toFloat()).toFloat())
        content.setTranslationY((-Math.round((WANDER_UP * k * (1 + sin(rise)) / 2).toFloat())).toFloat())
        content.setAlpha(alpha * (1 - (1 - CHROME_DIM) * fadeIn(up - startedAt - DIM_AFTER_MS, DIM_MS)))
        content.draw(canvas)
    }

    private fun drawContent(c: Canvas) {
        val cx = w / 2f
        val clockCap = Ui.cap(clockPaint)
        val clockBase = h * 0.5f + clockCap / 2 - 10 * k
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint)
        c.drawText(clock, cx, clockBase, clockPaint)

        // Weather: symbol, temperature, high and low, centered under the clock.
        val wx = data.weather
        if (!wx.temp.isNaN() && Prefs.weather()) {
            val base = clockBase + 76 * k
            val symbol = Symbols.weather(wx.code, wx.isDay)
            val now = HomeData.degrees(wx.temp)
            val range = when {
                Ui.ru -> "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                Ui.en -> "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                Ui.de -> "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                else -> "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min)
            }
            val sw = Symbols.width(context, symbol, 32 * k)
            val width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range)
            var x = cx - width / 2
            Symbols.draw(context, c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF.toInt())
            x += sw + 12 * k
            c.drawText(now, x, base, weatherPaint)
            x += weatherPaint.measureText(now) + 18 * k
            c.drawText(range, x, base, weatherDim)
        }

        // Prices near the bottom edge: small white logo + value, all four on one line.
        val cap = Ui.cap(quotePaint)
        val spacing = 56 * k
        val gap = 10 * k
        val coins = HomeData.active()
        val values = arrayOfNulls<String>(HomeData.coinCount())
        var total = spacing * (values.size - 1)
        for (i in values.indices) {
            values[i] = HomeData.price(data.prices[coins[i]])
            total += Glyphs.width(context, HomeData.COINS[coins[i]], cap) + gap + quotePaint.measureText(values[i])
        }
        var x = cx - total / 2
        val base = h - 92 * k
        for (i in values.indices) {
            val gw = Glyphs.width(context, HomeData.COINS[coins[i]], cap)
            Glyphs.coin(context, c, HomeData.COINS[coins[i]], x + gw / 2, base - cap / 2, cap, 0x80FFFFFF.toInt())
            x += gw + gap
            c.drawText(values[i]!!, x, base, quotePaint)
            x += quotePaint.measureText(values[i]) + spacing
        }
        val stale = HomeData.staleness(data.pricesAt, 5 * 60_000L)
        if (!stale.isEmpty() && values.isNotEmpty()) {
            c.drawText(Ui.tr("cotações de ", "prices from ") + stale, cx, base + 38 * k, stalePaint)
        }
    }

    private companion object {
        const val SHOW_MS = 30_000L
        const val FADE_MS = 3_000L
        const val FIRST_MS = 1_600L
        const val ZOOM = 0.1f
        /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
        const val PW = 2112
        const val PH = 1188
        const val MARGIN = 80f
        const val RIGHT = 1840f
        const val TITLE_W = 900f
        const val META_BASE = 1008f
        const val DATE_BASE = 92f
        const val CLOCK_TOP = 120f
        const val CLOCK_SIZE = 200f
        const val QUOTES_GAP = 52f
        const val DRIFT_PERIOD_S = 600.0
        /**
         * With photos, the corner and the caption drift on the same path, from a point on it drawn at each start (so
         * short dreams don't all wear the same spot), 32 px across (not 40: the corner's right edge and the caption's
         * left one stay 48 px from the screen's edge, the TV's overscan margin) and 26 down. After ten minutes the
         * corner and the caption dim to 70% and the photo to 85%. Both against the burn-in of an OLED (the Rokid Max)
         * and the image retention of an LCD.
         */
        const val DRIFT_X = 32f
        const val DRIFT_Y = 26f
        const val DIM_AFTER_MS = 600_000L
        const val DIM_MS = 4_000L
        const val CHROME_DIM = 0.7f
        const val PHOTO_DIM = 0.85f
        /**
         * Without photos the clock, date, weather and prices wander as one block, also from a point drawn at each
         * start: 360 px each way across and up to 260 px up (never down: the prices already sit near the bottom edge),
         * across on the 600 s period and up on 437 s, so the path doesn't repeat for days. They dim to 70% after ten
         * minutes too.
         */
        const val WANDER_X = 360f
        const val WANDER_UP = 260f
        const val WANDER_PERIOD_Y_S = 437.0

        fun fadeIn(elapsed: Long, duration: Long): Float {
            val t = Ui.clamp(elapsed / duration.toFloat())
            return t * t * (3 - 2 * t)
        }

        /**
         * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in
         * the background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow
         * zoom, they move a little along with the photo, which makes no difference to the text.
         */
        fun withScrims(photo: Bitmap, lightCaption: Float, lightClock: Float): Bitmap {
            val w = photo.width
            val h = photo.height
            val c = Canvas(photo)
            val p = Paint(Paint.DITHER_FLAG)
            val caption = scrimStrength(lightCaption, 0.5f)
            val corner = scrimStrength(lightClock, 0.42f)
            p.shader = LinearGradient(0f, h.toFloat(), 0f, h * 0.42f,
                intArrayOf(black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.42f, w.toFloat(), h.toFloat(), p)
            val around = RadialGradient(0f, h.toFloat(), w * 0.7f,
                intArrayOf(black(caption), black(0.55f * caption), black(0.15f * caption), 0),
                floatArrayOf(0f, 0.4f, 0.72f, 1f), Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(1f, 0.62f, 0f, h.toFloat())
            around.setLocalMatrix(m)
            p.shader = around
            c.drawRect(0f, h - w * 0.7f * 0.62f, w * 0.7f, h.toFloat(), p)
            p.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(black(0.3f), black(0.075f), 0),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h * 0.3f, p)
            val cx = w * 0.86f
            val cy = h * 0.15f
            val clockSide = RadialGradient(cx, cy, w * 0.36f,
                intArrayOf(black(corner), black(0.6f * corner), black(0.15f * corner), 0),
                floatArrayOf(0f, 0.42f, 0.75f, 1f), Shader.TileMode.CLAMP)
            val mc = Matrix()
            mc.setScale(1f, 0.72f, cx, cy)
            clockSide.setLocalMatrix(mc)
            p.shader = clockSide
            c.drawRect(w * 0.5f, 0f, w.toFloat(), cy + w * 0.36f * 0.72f, p)
            return photo
        }

        fun black(a: Float): Int = Math.round(max(0f, min(1f, a)) * 255) shl 24

        fun scrimStrength(light: Float, base: Float): Float = max(base - 0.1f, min(0.78f, base + (light - 0.3f) * 0.9f))

        fun nearest(b: Bitmap): BitmapShader {
            val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST)
            return s
        }

        /**
         * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the
         * middle of the strokes), its slope (for the lens and the highlight) and the shadow.
         */
        fun makeGlass(text: String, paint: Paint, right: Float, top: Float, k: Float): Glass {
            val ink = Rect()
            paint.getTextBounds(text, 0, text.length, ink)
            val pad = Math.round(40 * k)
            val w = ink.width() + 2 * pad
            val h = ink.height() + 2 * pad
            val cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(cover).drawText(text, (pad - ink.left).toFloat(), (pad - ink.top).toFloat(), paint)
            val px = IntArray(w * h)
            cover.getPixels(px, 0, w, 0, 0, w, h)
            val alpha = IntArray(px.size)
            for (i in px.indices) alpha[i] = (px[i] ushr 24) * 16
            val height = blur(alpha, w, h, max(2, Math.round(6 * k)), 3)
            val soft = blur(alpha, w, h, max(3, Math.round(12 * k)), 3)
            var max = 1f
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gx = (height[i + 1] - height[i - 1]).toFloat()
                    val gy = (height[i + w] - height[i - w]).toFloat()
                    max = max(max, sqrt((gx * gx + gy * gy).toDouble()).toFloat())
                }
            }
            val slope = IntArray(px.size)
            val shadow = IntArray(px.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val gx = (if (x > 0 && x < w - 1) height[i + 1] - height[i - 1] else 0).toFloat()
                    val gy = (if (y > 0 && y < h - 1) height[i + w] - height[i - w] else 0).toFloat()
                    val r = 128 + Math.round(127 * gx / max)
                    val g = 128 + Math.round(127 * gy / max)
                    val b = min(255, height[i] / 16)
                    slope[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                    shadow[i] = min(255, soft[i] / 16) shl 24
                }
            }
            val x = Math.round(right - ink.width()) - pad
            val y = Math.round(top) - pad
            return Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad.toFloat(),
                max(1, ink.height()).toFloat())
        }

        /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
        fun blur(src: IntArray, w: Int, h: Int, r: Int, passes: Int): IntArray {
            val a = src.clone()
            val b = IntArray(src.size)
            for (pass in 0 until passes) {
                box(a, b, w, h, r, true)
                box(b, a, w, h, r, false)
            }
            return a
        }

        fun box(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val n = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val step = if (horizontal) 1 else w
            val lineStep = if (horizontal) w else 1
            val div = 2 * r + 1
            for (line in 0 until lines) {
                val base = line * lineStep
                var sum = 0
                for (i in 0..min(r, n - 1)) sum += input[base + i * step]
                for (i in 0 until n) {
                    out[base + i * step] = (sum + div / 2) / div
                    val add = i + r + 1
                    val sub = i - r
                    if (add < n) sum += input[base + add * step]
                    if (sub >= 0) sum -= input[base + sub * step]
                }
            }
        }
    }
}
