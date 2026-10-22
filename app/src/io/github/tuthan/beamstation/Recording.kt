package io.github.tuthan.beamstation

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Build
import kotlin.math.roundToInt

/**
 * A recorded drawing that can be replayed with an alpha and a translation: a RenderNode on Android 10+, a Picture on
 * Android 9 (no RenderNode). Only Node touches RenderNode, so the classes that use Recording still verify on Android 9.
 */
internal abstract class Recording {
    abstract fun beginRecording(width: Int, height: Int): Canvas
    abstract fun endRecording()
    abstract fun setPosition(left: Int, top: Int, right: Int, bottom: Int)
    /** On Android 9 the layer is skipped: the alpha is applied with saveLayerAlpha only when it's below 1. */
    abstract fun setUseCompositingLayer(use: Boolean, paint: Paint?)
    abstract fun setAlpha(alpha: Float)
    abstract fun setTranslationX(x: Float)
    abstract fun setTranslationY(y: Float)
    abstract fun draw(c: Canvas)
    /**
     * Content that never overlaps itself (lines of text) can take an alpha below 1 per primitive instead of through a
     * layer the size of the recording: on Android 10+ this skips that layer on every frame drawn with such an alpha.
     * On Android 9 the picture keeps its saveLayerAlpha.
     */
    abstract fun setHasOverlappingRendering(has: Boolean)

    private class Node(name: String) : Recording() {
        private val node = RenderNode(name)

        override fun beginRecording(width: Int, height: Int): Canvas = node.beginRecording(width, height)
        override fun endRecording() = node.endRecording()
        override fun setPosition(left: Int, top: Int, right: Int, bottom: Int) {
            node.setPosition(left, top, right, bottom)
        }
        override fun setUseCompositingLayer(use: Boolean, paint: Paint?) {
            node.setUseCompositingLayer(use, paint)
        }
        override fun setAlpha(alpha: Float) {
            node.setAlpha(alpha)
        }
        override fun setTranslationX(x: Float) {
            node.setTranslationX(x)
        }
        override fun setTranslationY(y: Float) {
            node.setTranslationY(y)
        }
        override fun draw(c: Canvas) = c.drawRenderNode(node)
        override fun setHasOverlappingRendering(has: Boolean) {
            node.setHasOverlappingRendering(has)
        }
    }

    private class Pic : Recording() {
        private val picture = Picture()
        private var alpha = 1f
        private var tx = 0f
        private var ty = 0f
        private var left = 0
        private var top = 0
        private var right = 0
        private var bottom = 0

        override fun beginRecording(width: Int, height: Int): Canvas = picture.beginRecording(width, height)
        override fun endRecording() = picture.endRecording()

        override fun setPosition(left: Int, top: Int, right: Int, bottom: Int) {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

        override fun setUseCompositingLayer(use: Boolean, paint: Paint?) {}
        override fun setAlpha(alpha: Float) {
            this.alpha = alpha.coerceIn(0f, 1f)
        }
        override fun setTranslationX(x: Float) {
            tx = x
        }
        override fun setTranslationY(y: Float) {
            ty = y
        }
        override fun setHasOverlappingRendering(has: Boolean) {}

        override fun draw(c: Canvas) {
            if (alpha <= 0.004f) return
            val save = if (alpha < 1) c.saveLayerAlpha(left + tx, top + ty, right + tx, bottom + ty, (alpha * 255).roundToInt())
            else c.save()
            c.translate(left + tx, top + ty)
            c.drawPicture(picture)
            c.restoreToCount(save)
        }
    }

    companion object {
        @JvmStatic
        fun create(name: String): Recording = if (Build.VERSION.SDK_INT >= 29) Node(name) else Pic()
    }
}
