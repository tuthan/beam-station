package io.github.tuthan.beamstation

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Blurred rounded-rectangle shadows for older GPUs. A BlurMaskFilter is rasterized on the CPU and uploaded again every
 * time the shape changes size (every frame of a focus animation): on a Mali-450 (Mi Box 3, Android 9) that alone took
 * ~40 ms a frame. Here each (corner radius, blur) is blurred once into a small alpha bitmap and drawn as a nine-slice
 * (corners as they are, edges and center stretched), tinted with the paint's color.
 * Android 12+ keeps drawing the real BlurMaskFilter.
 */
internal class Shadows {
    private val cache = HashMap<Long, Bitmap>()
    private val tint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val xs = FloatArray(4)
    private val ys = FloatArray(4)
    private val sx = IntArray(4)
    private val sy = IntArray(4)

    /** Draws `shape` (radius `r`) with `paint`, whose BlurMaskFilter was made with radius `blur`. */
    fun draw(c: Canvas, shape: RectF, r: Float, blur: Float, paint: Paint) {
        if (!Ui.LEGACY) {
            c.drawRoundRect(shape, r, r, paint)
            return
        }
        val radius = max(0, r.roundToInt())
        val b = max(1, blur.roundToInt())
        val m = ceil(b * 1.8f).toInt() + 1        // how far the blur spreads outside the shape
        val corner = radius + 2 * m               // the slice that holds the curve and the blur on both sides of it
        val w = slices(shape.left - m, shape.right + m, corner, xs, sx)
        val h = slices(shape.top - m, shape.bottom + m, corner, ys, sy)
        val mask = mask(radius, b, m, w, h)
        tint.color = paint.color
        for (i in 0 until 3) {
            if (sy[i + 1] == sy[i]) continue
            for (j in 0 until 3) {
                if (sx[j + 1] == sx[j]) continue
                src.set(sx[j], sy[i], sx[j + 1], sy[i + 1])
                dst.set(xs[j], ys[i], xs[j + 1], ys[i + 1])
                c.drawBitmap(mask, src, dst, tint)
            }
        }
    }

    private fun mask(radius: Int, b: Int, m: Int, w: Int, h: Int): Bitmap {
        val key = (radius.toLong() shl 48) or (b.toLong() shl 32) or (w.toLong() shl 16) or h.toLong()
        cache[key]?.let { return it }
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF000000.toInt()
        p.maskFilter = BlurMaskFilter(b.toFloat(), BlurMaskFilter.Blur.NORMAL)
        Canvas(full).drawRoundRect(m.toFloat(), m.toFloat(), (w - m).toFloat(), (h - m).toFloat(),
            radius.toFloat(), radius.toFloat(), p)
        val mask = full.extractAlpha()
        full.recycle()
        if (cache.size > 64) cache.clear()     // focus animations step through a few sizes; keep it bounded
        cache[key] = mask
        return mask
    }
}

/**
 * One axis from `from` to `to`: long enough, three slices (corner, 1 px stretched, corner); too short for that, the
 * whole length in one slice, its size rounded to 8 px so a growing shape reuses a few bitmaps. Returns the size.
 */
private fun slices(from: Float, to: Float, corner: Int, out: FloatArray, src: IntArray): Int {
    if (to - from >= 2 * corner + 1) {
        val size = 2 * corner + 1
        out[0] = from; out[1] = from + corner; out[2] = to - corner; out[3] = to
        src[0] = 0; src[1] = corner; src[2] = corner + 1; src[3] = size
        return size
    }
    val size = max(8, ((to - from) / 8).roundToInt() * 8)
    out[0] = from; out[1] = from; out[2] = to; out[3] = to
    src[0] = 0; src[1] = 0; src[2] = size; src[3] = size
    return size
}
