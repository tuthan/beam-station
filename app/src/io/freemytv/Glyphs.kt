package io.freemytv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.SparseArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Official coin logos (BTC, ETH, SOL, USDT), flat and single-color: just the symbol, no disc or gradient
 * (res/drawable/coin_*.xml, vector drawables from the open cryptocurrency-icons set, CC0). The four symbols were
 * drawn on the same 32-unit frame with balanced visual "weight", so here they all use the same frame scale and only
 * the spare space on the sides is trimmed. Each size is drawn once into an exact-size bitmap and reused (sharp, no
 * resampling).
 */
internal object Glyphs {
    private val BITMAPS = SparseArray<Bitmap?>()
    private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val IDS = intArrayOf(R.drawable.coin_btc, R.drawable.coin_eth, R.drawable.coin_sol, R.drawable.coin_usdt)

    /** Draws the coin centered at (cx, cy), sized to the text next to it (cap height `cap`), in `color`. */
    @JvmStatic
    fun coin(context: Context, c: Canvas, coin: String, cx: Float, cy: Float, cap: Float, color: Int) {
        val b = bitmap(context, coin, frame(cap)) ?: return
        PAINT.color = color
        c.drawBitmap(b, (cx - b.width / 2f).roundToInt().toFloat(), (cy - b.height / 2f).roundToInt().toFloat(), PAINT)
    }

    /** Width the symbol takes up (for laying out the line). */
    @JvmStatic
    fun width(context: Context, coin: String, cap: Float): Float =
        bitmap(context, coin, frame(cap))?.width?.toFloat() ?: 0f

    /** Side of the 32-unit frame: the largest symbol (the ETH diamond) ends up ~1.3 × the cap height. */
    private fun frame(cap: Float) = max(12, (cap * 1.75f).roundToInt())

    private fun bitmap(context: Context, coin: String, frame: Int): Bitmap? {
        val slot = slot(coin)
        val key = (slot shl 16) or frame
        synchronized(BITMAPS) {
            if (BITMAPS.indexOfKey(key) >= 0) return BITMAPS[key]
            val b = context.getDrawable(IDS[slot])?.let { d ->
                val full = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, frame, frame)
                d.draw(Canvas(full))
                trimSides(full).extractAlpha()
            }
            BITMAPS.put(key, b)
            return b
        }
    }

    /** Trims only the empty columns on the sides (the frame height stays, so all coins center on the same line). */
    private fun trimSides(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        var left = w
        var right = -1
        val column = IntArray(h)
        for (x in 0 until w) {
            b.getPixels(column, 0, 1, x, 0, 1, h)
            if (column.any { (it ushr 24) > 8 }) {
                left = min(left, x)
                right = max(right, x)
            }
        }
        if (right < left) return b
        return Bitmap.createBitmap(b, left, 0, right - left + 1, h)
    }

    private fun slot(coin: String) = when (coin) {
        "BTC" -> 0
        "ETH" -> 1
        "SOL" -> 2
        else -> 3
    }
}
