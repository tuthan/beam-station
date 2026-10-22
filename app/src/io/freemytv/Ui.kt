package io.freemytv

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
internal object Ui {
    /** Android 9 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    @JvmField val LEGACY = Build.VERSION.SDK_INT < 31
    @JvmField val RU = Locale("ru", "RU")
    @JvmField val PT = Locale("pt", "BR")
    /** English, Portuguese, Spanish, German or Russian, from the settings or, with "auto", the TV's own language. */
    @JvmField @Volatile var language = pickLanguage()
    @JvmField @Volatile var en = "en" == language
    @JvmField @Volatile var es = "es" == language
    @JvmField @Volatile var de = "de" == language
    @JvmField @Volatile var ru = "ru" == language
    /** Null until init (a Paint takes null as the default font, as before). */
    @JvmField var thin: Typeface? = null
    @JvmField var light: Typeface? = null
    @JvmField var displayMedium: Typeface? = null
    @JvmField var displaySemibold: Typeface? = null
    @JvmField var regular: Typeface? = null
    @JvmField var medium: Typeface? = null
    @JvmField var semibold: Typeface? = null
    private var loaded = false
    private val BOUNDS = Rect()
    private val OVAL = RectF()
    private const val FONT = "fonts/InterVariable.ttf"

    @JvmStatic
    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        loaded = true
        val a = c.assets
        thin = font(a, 32, 200)
        light = font(a, 32, 300)
        displayMedium = font(a, 32, 500)
        displaySemibold = font(a, 32, 600)
        regular = font(a, 14, 400)
        medium = font(a, 14, 500)
        semibold = font(a, 14, 600)
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private fun font(a: AssetManager, opticalSize: Int, weight: Int): Typeface {
        if (has(a, FONT)) {
            try {
                val t: Typeface? = Typeface.Builder(a, FONT)
                    .setFontVariationSettings("'opsz' $opticalSize, 'wght' $weight").build()
                if (t != null) return t
            } catch (ignored: RuntimeException) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false)
    }

    /** "pt", "es", "de", "ru" or "en": the one picked (TV settings or config.properties), or with "auto" the TV's own, else English. */
    private fun pickLanguage(): String {
        val lang: String = Prefs.language()
        if (lang != "auto") return lang
        val tv = Locale.getDefault().language
        return if (tv == "pt" || tv == "es" || tv == "de" || tv == "ru") tv else "en"
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    @JvmStatic
    fun applyLanguage() {
        language = pickLanguage()
        en = "en" == language
        es = "es" == language
        de = "de" == language
        ru = "ru" == language
    }

    private fun has(a: AssetManager, path: String) = try {
        a.open(path).use { true }
    } catch (e: IOException) {
        false
    }

    @JvmStatic
    fun text(face: Typeface?, size: Float, color: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        p.typeface = face
        p.textSize = size
        p.color = color
        return p
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    @JvmStatic
    fun numbers(face: Typeface?, size: Float, color: Int): Paint {
        val p = text(face, size, color)
        p.fontFeatureSettings = "'tnum'"
        return p
    }

    /** Height of capital letters (and digits) above the baseline. */
    @JvmStatic
    fun cap(p: Paint): Float {
        synchronized(BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS)
            return -BOUNDS.top.toFloat()
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    @JvmStatic
    fun halfCap(p: Paint) = cap(p) / 2f

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    @JvmStatic
    fun leftBearing(p: Paint, s: String): Float {
        if (s.isEmpty()) return 0f
        synchronized(BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS)
            return BOUNDS.left.toFloat()
        }
    }

    @JvmStatic
    fun alpha(color: Int, a: Float): Int {
        val base = (color ushr 24) and 255
        return ((base * max(0f, min(1f, a))).roundToInt() shl 24) or (color and 0xFFFFFF)
    }

    @JvmStatic
    fun clamp(v: Float) = max(0f, min(1f, v))

    @JvmStatic
    fun tr(pt: String, english: String): String =
        if (de) German.text(english) else if (ru) Russian.text(english) else if (es) Spanish.text(english)
        else if (en) english else pt

    /** German comes from German.java here too (keyed by the English, with patterns for text built at runtime). */
    @JvmStatic
    fun tr(pt: String, english: String, spanish: String): String =
        if (de) German.text(english) else if (ru) Russian.text(english) else if (es) spanish else if (en) english else pt

    @JvmStatic
    fun locale(): Locale = if (ru) RU else if (es) spanish() else if (de) german() else if (en) Locale.US else PT

    /** The TV's own Spanish (es-MX, es-AR…) when it is in Spanish, Spain's otherwise. */
    private fun spanish(): Locale {
        val tv = Locale.getDefault()
        return if ("es" == tv.language) tv else Locale("es", "ES")
    }

    /** The TV's own German (de-AT, de-CH…) when it is in German, Germany's otherwise. */
    private fun german(): Locale {
        val tv = Locale.getDefault()
        return if ("de" == tv.language) tv else Locale.GERMANY
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    @JvmStatic
    fun clock(now: Long): String =
        if (en) SimpleDateFormat("h:mm", Locale.US).format(Date(now)) else SimpleDateFormat("HH:mm", locale()).format(Date(now))

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    @JvmStatic
    fun time(at: Long): String = once("time", at.toDouble()) {
        if (en) SimpleDateFormat("h:mm a", Locale.US).format(Date(at)) else SimpleDateFormat("H:mm", locale()).format(Date(at))
    }

    private val FORMATTED = HashMap<String, String>()

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    @JvmStatic
    fun once(kind: String, value: Double, format: () -> String): String {
        val key = "$kind|$language|$value"
        synchronized(FORMATTED) {
            return FORMATTED.getOrPut(key) {
                if (FORMATTED.size > 200) FORMATTED.clear()
                format()
            }
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    @JvmStatic
    fun date(now: Long): String = when {
        ru -> SimpleDateFormat("EEEE, d MMMM", locale()).format(Date(now))
        en -> SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(now))
        else -> SimpleDateFormat(if (de) "EEEE, d. MMMM" else "EEEE, d 'de' MMMM", locale()).format(Date(now))
    }

    /** Spinning arc (work in progress). */
    @JvmStatic
    fun spinner(c: Canvas, cx: Float, cy: Float, r: Float, seconds: Float, stroke: Paint) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke)
    }
}
