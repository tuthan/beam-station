package io.github.tuthan.stationar.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The HUD: one line at the top of your view (time, the Station's battery, the focused screen, the laptop), drawn on
 * a Canvas into a texture only when its text changes. GL thread.
 */
class Hud {
    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 12, 18, 24) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private var shown: String? = null
    var texture = 0
        private set

    /** Draws `line` if it changed (GL thread). Empty hides the HUD. */
    fun set(line: String) {
        if (line == shown) return
        shown = line
        bitmap.eraseColor(Color.TRANSPARENT)
        if (line.isNotEmpty()) {
            val w = (text.measureText(line) + 64f).coerceAtMost(W.toFloat())
            val r = RectF((W - w) / 2, 4f, (W + w) / 2, H - 4f)
            canvas.drawRoundRect(r, 30f, 30f, back)
            canvas.drawText(line, W / 2f, H / 2f + 12f, text)
        }
        if (texture == 0) texture = Gl.texture2d(bitmap) else Gl.upload(texture, bitmap)
    }

    val visible: Boolean get() = !shown.isNullOrEmpty()

    fun release() {
        Gl.deleteTexture(texture)
        texture = 0
        shown = null
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    companion object {
        const val W = 1024
        const val H = 72
    }
}
