package io.github.tuthan.stationar.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.tuthan.stationar.core.scene.PanelLayout

/**
 * The AR panel's picture: its rows of tiles, the focused one lit, drawn on a Canvas into a texture whenever the
 * panel changes (GL thread). Icons are keyed by item id.
 */
class PanelView {
    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(236, 14, 20, 26) }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 50) }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 120, 112) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 234, 212)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 180, 195)
        textSize = 26f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(253, 186, 116)
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val r = RectF()
    var texture = 0
        private set

    fun draw(rows: List<PanelLayout.Row>, focusRow: Int, focusCol: Int, header: String, icons: Map<String, Bitmap>) {
        bitmap.eraseColor(Color.TRANSPARENT)
        r.set(0f, 0f, W.toFloat(), H.toFloat())
        canvas.drawRoundRect(r, 36f, 36f, back)
        canvas.drawText(header, PAD, 58f, heading)
        // Rows from the one that keeps the focused row in view.
        val visible = ((H - TOP) / ROW_H).toInt()
        val first = (focusRow - visible + 1).coerceAtLeast(0)
        var y = TOP
        for (ri in first until rows.size) {
            if (y + ROW_H > H) break
            val row = rows[ri]
            row.title?.let { canvas.drawText(it, PAD, y + 26f, title) }
            val ty = y + 36f
            for (ci in row.items.indices) {
                val item = row.items[ci]
                val x = PAD + ci * (TILE_W + GAP)
                r.set(x, ty, x + TILE_W, ty + TILE_H)
                val focused = ri == focusRow && ci == focusCol
                canvas.drawRoundRect(r, 18f, 18f, if (focused) lit else tile)
                if (focused) canvas.drawRoundRect(r, 18f, 18f, ring)
                val icon = icons[item.id]
                val cx = x + TILE_W / 2
                if (icon != null) {
                    val s = 52f
                    r.set(cx - s / 2, ty + 12f, cx + s / 2, ty + 12f + s)
                    canvas.drawBitmap(icon, null, r, null)
                    drawFitted(item.label, cx, ty + TILE_H - 18f)
                } else {
                    drawFitted(item.label, cx, ty + TILE_H / 2 + (if (item.detail.isEmpty()) 9f else -2f))
                }
                if (item.detail.isNotEmpty()) canvas.drawText(item.detail, cx, ty + TILE_H - (if (icon != null) 0f else 16f) - 2f, detail)
            }
            y += ROW_H
        }
        if (texture == 0) texture = Gl.texture2d(bitmap) else Gl.upload(texture, bitmap)
    }

    /** A label shrunk to fit its tile. */
    private fun drawFitted(text: String, cx: Float, baseline: Float) {
        label.textSize = 26f
        while (label.measureText(text) > TILE_W - 16 && label.textSize > 16f) label.textSize -= 1f
        canvas.drawText(text, cx, baseline, label)
    }

    fun release() {
        Gl.deleteTexture(texture)
        texture = 0
    }

    companion object {
        const val W = 1280
        const val H = 760
        private const val PAD = 40f
        private const val TOP = 84f
        private const val ROW_H = 168f
        private const val TILE_W = 186f
        private const val TILE_H = 118f
        private const val GAP = 14f
        /** Tiles per row that fit: (1280 - 2 * 40 + 14) / (186 + 14). */
        const val PER_ROW = 6
    }
}
