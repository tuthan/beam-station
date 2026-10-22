package io.freemytv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.IOException

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
internal object Symbols {
    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private const val PX_PER_POINT = 96 * 1.5f
    private val CACHE = HashMap<String?, Bitmap?>()
    private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val RECT = RectF()

    /** Null for a name with no symbol (looked for once). */
    @JvmStatic
    fun get(c: Context, name: String?): Bitmap? {
        synchronized(CACHE) {
            if (CACHE.containsKey(name)) return CACHE[name]
            val b = try {
                c.assets.open("symbols/$name.png").use { BitmapFactory.decodeStream(it) }
                    ?.extractAlpha()?.apply { setHasMipMap(true) }
            } catch (ignored: IOException) {
                null
            }
            CACHE[name] = b
            return b
        }
    }

    @JvmStatic
    fun width(c: Context, name: String?, pointSize: Float): Float {
        val b = get(c, name) ?: return 0f
        return b.width * pointSize / PX_PER_POINT
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    @JvmStatic
    fun draw(c: Context, canvas: Canvas, name: String?, cx: Float, cy: Float, pointSize: Float, color: Int) {
        val b = get(c, name) ?: return
        synchronized(PAINT) {   // card loading (another thread) also draws symbols
            val w = b.width * pointSize / PX_PER_POINT
            val h = b.height * pointSize / PX_PER_POINT
            PAINT.color = color
            RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawBitmap(b, null, RECT, PAINT)
        }
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    @JvmStatic
    fun weather(code: Int, day: Boolean): String = when {
        code <= 1 -> if (day) "sunny" else "clear_night"
        code == 2 -> if (day) "partly_cloudy_day" else "partly_cloudy_night"
        code == 3 -> "cloud"
        code == 45 || code == 48 -> "foggy"
        code in 51..57 -> "rainy"
        code == 65 || code == 67 || code == 82 -> "rainy"
        code in 71..77 || code == 85 || code == 86 -> "snowflake"
        code >= 95 -> "thunderstorm"
        else -> "rainy"
    }
}
