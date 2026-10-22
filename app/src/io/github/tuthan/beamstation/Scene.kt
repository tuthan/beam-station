package io.github.tuthan.beamstation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 */
internal class Scene(private val withGlass: Boolean) {
    @JvmField val palette = Shaders.Palette()
    private var aurora: RuntimeShader? = null
    private var glass: RuntimeShader? = null
    private var liquid: RuntimeShader? = null
    private val frost = Frost()
    private val auroraPaint = Paint()
    private val glassPaint = Paint()
    private val liquidPaint = Paint()
    private val flatFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flatRim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallback = Paint()
    private val auroraNode = Recording.create("aurora")
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var w = 1
    private var h = 1
    private var lw = 1
    private var lh = 1
    private var speed = 1f
    private var baseDim = 0f
    private var dim = 0f
    private var time = 0f
    private var auroraDirty = true
    private var imageFrost = false
    private var imageGlass = false
    private var frostImage: Bitmap? = null
    private var glassA: Bitmap? = null
    private var glassB: Bitmap? = null
    private var glassShaderA: BitmapShader? = null

    constructor() : this(true)

    init {
        try {
            aurora = RuntimeShader(Shaders.AURORA)
            glass = RuntimeShader(Shaders.GLASS)
            liquid = RuntimeShader(Shaders.LIQUID)
            auroraPaint.shader = aurora
            glassPaint.shader = glass
            liquidPaint.shader = liquid
        } catch (e: RuntimeException) {   // Android 9 to 12: no AGSL, flat look instead
            noShaders(e)
        } catch (e: LinkageError) {
            noShaders(e)
        }
        auroraNode.setUseCompositingLayer(true, layerPaint)
        flatRim.style = Paint.Style.STROKE
        flatRim.color = 0x33FFFFFF
        flatRim.strokeWidth = 1.5f
    }

    private fun noShaders(e: Throwable) {
        AppLog.w("shader unavailable, using gradient: " + e.message)
        aurora = null
        glass = null
        liquid = null
    }

    fun setSize(width: Int, height: Int) {
        w = max(1, width)
        h = max(1, height)
        lw = max(1, Math.round(w / 4f))
        lh = max(1, Math.round(h / 4f))
        auroraNode.setPosition(0, 0, lw, lh)
        val aurora = aurora
        if (aurora != null) {
            aurora.setFloatUniform("res", w.toFloat(), h.toFloat())
            aurora.setFloatUniform("layer", lw.toFloat(), lh.toFloat())
            setFrostScale()
            if (imageGlass) liquid!!.setFloatUniform("imageScale", Backdrop.GW.toFloat() / w, Backdrop.GH.toFloat() / h)
        }
        push()
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    fun setMotion(speed: Float, dim: Float) {
        this.speed = speed
        baseDim = dim
        push()
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    fun applyPalette() {
        push()
        fallback.shader =
            LinearGradient(0f, 0f, 0f, h.toFloat(), palette.color(1), palette.color(0), Shader.TileMode.CLAMP)
    }

    private fun push() {
        dim = min(0.6f, baseDim + max(0f, min(0.22f, (palette.brightest() - 0.45f) * 0.5f)))
        val aurora = aurora ?: return
        aurora.setFloatUniform("speed", speed)
        aurora.setFloatUniform("dim", dim)
        palette.apply(aurora)
        advance(time)
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    fun advance(seconds: Float) {
        time = seconds
        val aurora = aurora ?: return
        aurora.setFloatUniform("t", seconds)
        if (withGlass && (!imageFrost || !imageGlass)) {
            val s = frost.render(seconds, speed, dim, palette.c, w.toFloat() / h)
            if (!imageFrost) glass!!.setInputShader("frost", s)
            if (!imageGlass) {
                val liquid = liquid!!
                liquid.setInputShader("imageA", s)
                liquid.setInputShader("imageB", s)
                liquid.setFloatUniform("mixB", 0f)
                liquid.setFloatUniform("imageScale", Frost.W.toFloat() / w, Frost.H.toFloat() / h)
            }
        }
        auroraDirty = true
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    fun setGlassImage(pixels: IntArray?) {
        setGlassImages(pixels, null)
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    fun setGlassImages(from: IntArray?, to: IntArray?) {
        imageGlass = true
        val a = fill(glassA, from)
        glassA = a
        if (to != null) glassB = fill(glassB, to)
        val liquid = liquid ?: return
        val shaderA = linear(a)
        glassShaderA = shaderA
        liquid.setInputShader("imageA", shaderA)
        liquid.setInputShader("imageB", if (to != null) linear(glassB!!) else shaderA)
        liquid.setFloatUniform("mixB", 0f)
        liquid.setFloatUniform("imageScale", Backdrop.GW.toFloat() / w, Backdrop.GH.toFloat() / h)
    }

    fun setGlassMix(t: Float) {
        liquid?.setFloatUniform("mixB", max(0f, min(1f, t)))
    }

    /** The liquid glass goes back to the frosted aurora. */
    fun clearGlass() {
        if (!imageGlass) return
        imageGlass = false
        advance(time)
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    fun liquid(c: Canvas, r: RectF, radius: Float, tint: Int, tintAlpha: Float, lift: Float, band: Float,
               refraction: Float, specular: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        val liquid = liquid
        if (liquid == null) {
            glass(c, r, radius, tint, max(0.5f, tintAlpha), 0, 0f, lift, 1f, alpha)
            return
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY())
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2)
        liquid.setFloatUniform("radius", min(radius, min(r.width(), r.height()) / 2))
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f,
            tintAlpha)
        liquid.setFloatUniform("lift", lift)
        liquid.setFloatUniform("band", max(1f, band))
        liquid.setFloatUniform("refraction", refraction)
        liquid.setFloatUniform("specular", specular)
        liquidPaint.alpha = Math.round(255 * min(1f, alpha))
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint)
        liquidPaint.alpha = 255
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    fun setFrost(pixels: IntArray?) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888)
        val image = frostImage!!
        image.setPixels(pixels!!, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH)   // null throws, as before
        imageFrost = true
        val glass = glass ?: return
        val s = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.filterMode = BitmapShader.FILTER_MODE_LINEAR
        glass.setInputShader("frost", s)
        setFrostScale()
    }

    /** Back to the frosted aurora. */
    fun clearFrost() {
        if (!imageFrost) return
        imageFrost = false
        setFrostScale()
        advance(time)
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    fun frostImage(): Bitmap? = if (imageFrost) frostImage else null

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    fun setOffset(x: Float, y: Float) {
        glass?.setFloatUniform("offset", x, y)
        liquid?.setFloatUniform("offset", x, y)
    }

    private fun setFrostScale() {
        val glass = glass ?: return
        if (imageFrost) glass.setFloatUniform("frostScale", Backdrop.FW.toFloat() / w, Backdrop.FH.toFloat() / h)
        else glass.setFloatUniform("frostScale", Frost.W.toFloat() / w, Frost.H.toFloat() / h)
    }

    fun drawBackground(c: Canvas) {
        if (aurora == null) {
            if (fallback.shader == null) applyPalette()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fallback)
            return
        }
        if (auroraDirty) {
            val rc = auroraNode.beginRecording(lw, lh)
            try {
                rc.drawPaint(auroraPaint)
            } finally {
                auroraNode.endRecording()
            }
            auroraDirty = false
        }
        c.save()
        c.scale(w.toFloat() / lw, h.toFloat() / lh)
        auroraNode.draw(c)
        c.restore()
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    fun glass(c: Canvas, r: RectF, radius: Float, tint: Int, tintAlpha: Float, glow: Int, glowAlpha: Float, lift: Float,
              body: Float) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1f)
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    fun glass(c: Canvas, r: RectF, radius: Float, tint: Int, tintAlpha: Float, glow: Int, glowAlpha: Float, lift: Float,
              body: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        val glass = glass
        if (glass == null) {
            if (body > 0f) {
                flatFill.color = Ui.alpha(tint or 0xFF000000.toInt(), max(0.35f, tintAlpha))
                c.drawRoundRect(r, radius, radius, flatFill)
            }
            c.drawRoundRect(r, radius, radius, flatRim)
            return
        }
        val hw = r.width() / 2
        val hh = r.height() / 2
        val inner = min(hw, hh)
        glass.setFloatUniform("center", r.centerX(), r.centerY())
        glass.setFloatUniform("halfSize", hw, hh)
        glass.setFloatUniform("invHalf", 1 / max(hw, 1f), 1 / max(hh, 1f))
        glass.setFloatUniform("radius", radius)
        glass.setFloatUniform("top", r.top)
        glass.setFloatUniform("depth", 1 / max(inner * 0.6f, 1f), min(24f, inner * 0.5f), 1 / max(inner * 0.9f, 1f),
            1 / max(r.height(), 1f))
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f,
            tintAlpha)
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f,
            glowAlpha)
        glass.setFloatUniform("lift", lift)
        glass.setFloatUniform("body", body)
        glassPaint.alpha = Math.round(255 * min(1f, alpha))
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint)
        glassPaint.alpha = 255
    }

    private companion object {
        fun fill(bitmap: Bitmap?, pixels: IntArray?): Bitmap {
            val b = bitmap ?: Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888)
            b.setPixels(pixels!!, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH)   // null throws, as before
            return b
        }

        fun linear(b: Bitmap): BitmapShader {
            val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            s.filterMode = BitmapShader.FILTER_MODE_LINEAR
            return s
        }
    }
}
