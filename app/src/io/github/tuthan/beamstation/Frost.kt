package io.github.tuthan.beamstation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The frosted glass background: the aurora without detail (the same math as `aurora(uv, 0)` in Shaders) in a 48×27
 * image, computed on the CPU at every aurora step (~1,300 pixels, under 1 ms). The glass shader scales it up with
 * linear filtering, which by itself is the blur.
 */
internal class Frost {
    private val pixels = IntArray(W * H)
    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    /** Recomputes and returns a new shader (a BitmapShader keeps a copy of the bitmap as it was when created). */
    fun render(t: Float, speed: Float, dim: Float, palette: FloatArray, aspect: Float): BitmapShader {
        val tt = t * 0.035f * speed
        for (j in 0 until H) {
            val v = (j + 0.5f) / H
            for (i in 0 until W) {
                val u = (i + 0.5f) / W
                pixels[j * W + i] = color(u, v, tt, dim, palette, aspect)
            }
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return filter(shader, BitmapShader.FILTER_MODE_LINEAR)
    }

    companion object {
        const val W = 48
        const val H = 27

        /** BitmapShader.setFilterMode is Android 13+; older ones filter through the Paint. */
        @JvmStatic
        fun filter(s: BitmapShader, mode: Int): BitmapShader {
            if (Build.VERSION.SDK_INT >= 33) s.filterMode = mode
            return s
        }
    }
}

private fun color(u: Float, v: Float, tt: Float, dim: Float, c: FloatArray, aspect: Float): Int {
    val px = u * aspect * 1.7f
    val py = v * 1.7f
    val qx = fbm2(px, py + tt)
    val qy = fbm2(px + 5.2f - tt, py + 1.3f - tt)
    val n = low(px + 2 * qx + 1.7f + tt, py + 2 * qy + 9.2f)
    val band = smoothstep(0.02f, 0.38f, v) * (1 - smoothstep(0.5f, 0.92f, v))
    val curtain = smoothstep(0.35f, 0.85f, n) * (0.35f + 0.65f * band)
    val m = smoothstep(0.15f, 0.85f, n)
    val k = curtain * 0.85f
    val vig = 1 - 0.38f * smoothstep(0.35f, 1f, v) - 0.2f * smoothstep(0.45f, 0.95f, abs(u - 0.5f) * 1.6f)
    val scrim = 1 - 0.22f * (1 - smoothstep(0f, 0.42f, v))
    val gain = vig * scrim * (1 - dim)
    var out = 0xFF000000.toInt()
    for (ch in 0 until 3) {
        var col = c[ch] + (c[3 + ch] - c[ch]) * m
        col += (c[6 + ch] - col) * k
        val b = (max(0f, min(1f, col * gain)) * 255).roundToInt()
        out = out or (b shl (16 - 8 * ch))
    }
    return out
}

private fun hash(x0: Float, y0: Float): Float {
    val x = fract(x0 * 123.34f)
    val y = fract(y0 * 456.21f)
    val d = x * (x + 45.32f) + y * (y + 45.32f)
    return fract((x + d) * (y + d))
}

private fun noise(x: Float, y: Float): Float {
    val ix = floor(x)
    val iy = floor(y)
    val fx = x - ix
    val fy = y - iy
    val ux = fx * fx * (3 - 2 * fx)
    val uy = fy * fy * (3 - 2 * fy)
    val a = hash(ix, iy)
    val b = hash(ix + 1, iy)
    val c = hash(ix, iy + 1)
    val d = hash(ix + 1, iy + 1)
    val top = a + (b - a) * ux
    val bottom = c + (d - c) * ux
    return top + (bottom - top) * uy
}

private fun fbm2(x: Float, y: Float) = 0.6f * noise(x, y) + 0.3f * noise(x * 2.03f + 17, y * 2.03f + 17)

/** The first 2 octaves + the mean of the rest (the shader's fbm without detail). */
private fun low(x: Float, y: Float) = 0.5f * noise(x, y) + 0.25f * noise(x * 2.03f + 17, y * 2.03f + 17) + 0.09375f

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = max(0f, min(1f, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)
}

private fun fract(v: Float) = v - floor(v)
