package io.github.tuthan.stationar.sources

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import io.github.tuthan.stationar.render.Gl

/**
 * The test card: a 1920x1080 grid with a centre cross and text at 12, 16 and 24 px, drawn once. It shows whether the
 * screens stay put as you turn your head (the grid against something in the room), and how sharp they are (the 16 px
 * line should read at 100 % size). It also tells you the keys.
 */
class TestCardSource(private val glasses: Boolean) : Source(NAME) {
    override val external = false

    override fun attach() {
        val bitmap = draw(glasses)
        texture = Gl.texture2d(bitmap)
        bitmap.recycle()
        System.arraycopy(Gl.FLIP_V, 0, texMatrix, 0, 16)
        live = true
    }

    override fun detach() {
        Gl.deleteTexture(texture)
        texture = 0
    }

    companion object {
        const val NAME = "card"
        const val W = 1920
        const val H = 1080

        fun draw(glasses: Boolean): Bitmap {
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.rgb(14, 20, 26))
            val line = Paint().apply { color = Color.rgb(46, 64, 78); strokeWidth = 1f }
            val major = Paint().apply { color = Color.rgb(86, 120, 140); strokeWidth = 2f }
            for (x in 0..W step 48) c.drawLine(x.toFloat(), 0f, x.toFloat(), H.toFloat(), if (x % 240 == 0) major else line)
            for (y in 0..H step 48) c.drawLine(0f, y.toFloat(), W.toFloat(), y.toFloat(), if (y % 240 == 0) major else line)
            // The edges and the centre cross, in the teal of the app's icon.
            val accent = Paint().apply { color = Color.rgb(45, 212, 191); strokeWidth = 4f; style = Paint.Style.STROKE }
            c.drawRect(2f, 2f, W - 2f, H - 2f, accent)
            c.drawLine(W / 2f - 60, H / 2f, W / 2f + 60, H / 2f, accent)
            c.drawLine(W / 2f, H / 2f - 60, W / 2f, H / 2f + 60, accent)
            c.drawCircle(W / 2f, H / 2f, 30f, accent)

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            text.textSize = 64f
            c.drawText("Station AR", 96f, 150f, text)
            text.textSize = 32f
            text.color = Color.rgb(200, 220, 230)
            val hints = if (glasses) {
                listOf("Hold OK: put this screen in front of you", "Menu: apps, layouts and settings", "Back: leave AR")
            } else {
                listOf("No glasses: the D-pad turns the view", "Menu: apps, layouts and settings", "Back: leave AR")
            }
            var y = 230f
            for (h in hints) {
                c.drawText(h, 96f, y, text)
                y += 52f
            }
            text.color = Color.WHITE
            val sample = "The quick brown fox jumps over the lazy dog 0123456789"
            y = H - 260f
            for (size in intArrayOf(12, 16, 24)) {
                text.textSize = size.toFloat()
                c.drawText("$size px: $sample", 96f, y, text)
                y += size * 2.6f
            }
            text.textSize = 20f
            text.color = Color.rgb(140, 170, 185)
            c.drawText("Grid: 48 px; bold lines every 240 px. At 1:1 the Max shows 1920 px across 44°.", 96f, H - 60f, text)
            return b
        }
    }
}
