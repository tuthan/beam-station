package io.github.tuthan.beamstation

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, such as
 *    some 1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the cards (the photo without scrims, cropped wide and tall, already at the focused card size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
internal class Backdrop private constructor(
    @JvmField val image: Bitmap?,
    @JvmField val frost: IntArray,          // heavily blurred (frosted glass, bottom drawer)
    @JvmField val glass: IntArray?,         // lightly blurred (Liquid Glass: the photo shows through the glass)
    @JvmField val wide: Bitmap?,            // the photo on the cards
    @JvmField val tall: Bitmap?,
    @JvmField val light: IntArray?,         // by day: the frosted and glass versions, lightened
    @JvmField val lightGlass: IntArray?,
    @JvmField val nightFrost: IntArray?,    // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    @JvmField val nightGlass: IntArray?
) {
    // by day: the background (the lightened frosted version, scaled up on screen)
    @JvmField val lightImage: Bitmap? =
        if (light == null) null else Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888)
    // at night: the background (the darkened frosted version)
    @JvmField val nightImage: Bitmap? =
        if (nightFrost == null) null else Bitmap.createBitmap(nightFrost, FW, FH, Bitmap.Config.ARGB_8888)

    companion object {
        /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
        private val UI_SCALE = uiScale()
        @JvmField val W = ui(1920)
        @JvmField val H = ui(1080)
        const val FW = 192
        const val FH = 108
        const val GW = 480
        const val GH = 270
        /** The cards at their focused size (1.05×): the wide one and the two tall ones. */
        @JvmField val WIDE_W = ui(1092)
        @JvmField val WIDE_H = ui(464)
        @JvmField val TALL_W = ui(336)
        @JvmField val TALL_H = ui(464)

        private fun uiScale(): Float {
            val m = Resources.getSystem().displayMetrics
            val longest = max(m.widthPixels, m.heightPixels)
            return if (longest > 0) min(1f, longest / 1920f) else 1f
        }

        private fun ui(px: Int): Int = Math.round(px * UI_SCALE)

        @JvmStatic
        fun make(src: Bitmap, forLight: Boolean): Backdrop {
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val photo = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val scale = max(W.toFloat() / src.width, H.toFloat() / src.height)
            val sw = src.width * scale
            val sh = src.height * scale
            c.drawBitmap(src, null, RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo)
            src.recycle()
            if (forLight) {
                // Light design: everything comes from the photo without scrims.
                val wide = card(out, WIDE_W, WIDE_H)
                val tall = card(out, TALL_W, TALL_H)
                val frost = reduce(out, FW, FH, 3, 3)
                val light = whiten(frost.clone(), 0.82f)
                val glass = reduce(out, GW, GH, 2, 2)
                val lightGlass = whiten(glass.clone(), 0.74f)
                val night = darken(frost.clone(), 0.76f)
                val nightGlass = darken(glass, 0.56f)
                out.recycle()
                return Backdrop(null, frost, null, wide, tall, light, lightGlass, night, nightGlass)
            }

            val boost = max(0f, min(0.3f, (luminance(out, 0f, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f))
            val scrim = Paint(Paint.DITHER_FLAG)
            scrim.shader = LinearGradient(0f, 0f, W * 0.68f, 0f,
                intArrayOf(black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f),
                    0),
                floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), scrim)
            // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the
            // contrast).
            scrim.shader = LinearGradient(0f, H.toFloat(), 0f, H * 0.4f,
                intArrayOf(black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0),
                floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, H * 0.4f, W.toFloat(), H.toFloat(), scrim)
            // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
            scrim.shader = RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                intArrayOf(black(0.42f), black(0.18f), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.save()
            c.scale(1f, 0.32f, W * 0.8f, H * 0.65f)
            c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim)
            c.restore()
            scrim.shader = LinearGradient(0f, 0f, 0f, H * 0.24f, intArrayOf(black(0.34f), black(0.1f), 0),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H * 0.24f, scrim)

            val frost = reduce(out, FW, FH, 3, 3)
            val glass = reduce(out, GW, GH, 2, 2)
            return Backdrop(gpu(out), frost, glass, null, null, null, null, null, null)
        }

        /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
        const val PHOTO_FROST = 6

        /**
         * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus
         * the frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the
         * caption (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun photo(f: File, w: Int, h: Int, light: FloatArray?): Array<Bitmap> {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("unreadable image")
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 1
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) {
                opts.inSampleSize *= 2
            }
            val src = BitmapFactory.decodeFile(f.path, opts) ?: throw IOException("unreadable image")
            val ratio = w.toFloat() / h
            val cw = min(src.width, Math.round(src.height * ratio))
            val ch = min(src.height, Math.round(cw / ratio))
            var b = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
            if (b !== src) src.recycle()
            while (b.width / 2 >= w) {
                val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                b.recycle()
                b = half
            }
            var out = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
            if (out !== b) b.recycle()
            if (light != null && light.size >= 2) {
                light[0] = luminance(out, 0f, 0.6f, 0.5f, 0.95f)
                light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f)
            }
            val fw = w / PHOTO_FROST
            val fh = h / PHOTO_FROST
            val frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888)
            if (!out.isMutable) {
                val copy = out.copy(Bitmap.Config.ARGB_8888, true)
                out.recycle()
                out = copy
            }
            return arrayOf(out, frost)
        }

        /**
         * The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact
         * size.
         */
        private fun card(full: Bitmap, w: Int, h: Int): Bitmap {
            val ratio = w.toFloat() / h
            val cw = min(full.width, Math.round(full.height * ratio))
            val ch = min(full.height, Math.round(cw / ratio))
            var b = Bitmap.createBitmap(full, (full.width - cw) / 2, (full.height - ch) / 2, cw, ch)
            while (b.width / 2 >= w) {
                val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                if (b !== full) b.recycle()
                b = half
            }
            val out = Bitmap.createScaledBitmap(b, w, h, true)
            if (b !== full && b !== out) b.recycle()
            return gpu(out)
        }

        /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
        @JvmStatic
        fun gpu(b: Bitmap): Bitmap {
            val g = b.copy(Bitmap.Config.HARDWARE, false) ?: return b
            b.recycle()
            return g
        }

        /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
        private fun darken(px: IntArray, amount: Float): IntArray {
            val keep = 1 - amount
            val base = 11 * amount
            for (i in px.indices) {
                val p = px[i]
                val r = Math.round(((p shr 16) and 255) * keep + base)
                val g = Math.round(((p shr 8) and 255) * keep + base)
                val b = Math.round((p and 255) * keep + base + 2 * amount)
                px[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            return px
        }

        private fun whiten(px: IntArray, amount: Float): IntArray {
            for (i in px.indices) {
                val p = px[i]
                val r = Math.round(((p shr 16) and 255) * (1 - amount) + 255 * amount)
                val g = Math.round(((p shr 8) and 255) * (1 - amount) + 255 * amount)
                val b = Math.round((p and 255) * (1 - amount) + 255 * amount)
                px[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            return px
        }

        /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
        private fun reduce(full: Bitmap, w: Int, h: Int, radius: Int, passes: Int): IntArray {
            var b = full
            while (b.width / 2 >= w * 1.5f) {
                val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                if (b !== full) b.recycle()
                b = half
            }
            val small = Bitmap.createScaledBitmap(b, w, h, true)   // b itself when it already has that size
            if (b !== full && b !== small) b.recycle()
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== full) small.recycle()   // never the caller's bitmap
            val tmp = IntArray(px.size)
            for (pass in 0 until passes) {
                box(px, tmp, w, h, radius, true)
                box(tmp, px, w, h, radius, false)
            }
            return px
        }

        /** Moving average of radius r in one direction, repeating the edge pixels. */
        private fun box(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val n = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val step = if (horizontal) 1 else w
            val lineStep = if (horizontal) w else 1
            val k = 1f / (2 * r + 1)
            for (line in 0 until lines) {
                val base = line * lineStep
                var sr = 0f
                var sg = 0f
                var sb = 0f
                for (i in -r..r) {
                    val p = input[base + max(0, min(n - 1, i)) * step]
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                }
                for (i in 0 until n) {
                    out[base + i * step] = 0xFF000000.toInt() or (Math.round(sr * k) shl 16) or
                        (Math.round(sg * k) shl 8) or Math.round(sb * k)
                    val add = input[base + min(n - 1, i + r + 1) * step]
                    val sub = input[base + max(0, i - r) * step]
                    sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                    sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                    sb += (add and 255) - (sub and 255)
                }
            }
        }

        /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
        private fun luminance(b: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Float {
            var sum = 0f
            var count = 0
            for (j in 0 until 24) {
                for (i in 0 until 32) {
                    val x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.width - 1))
                    val y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.height - 1))
                    val p = b.getPixel(x, y)
                    sum += (0.2126f * ((p shr 16) and 255) + 0.7152f * ((p shr 8) and 255) + 0.0722f * (p and 255)) /
                        255f
                    count++
                }
            }
            return sum / count
        }

        private fun black(a: Float): Int = Math.round(max(0f, min(1f, a)) * 255) shl 24
    }
}
