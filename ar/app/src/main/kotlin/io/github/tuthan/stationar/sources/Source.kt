package io.github.tuthan.stationar.sources

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.render.Gl
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a screen shows, as a GL texture. [attach], [update] and [detach] run on the GL thread; [update] runs every
 * frame and allocates nothing.
 */
abstract class Source(val name: String) {
    /** True for a SurfaceTexture's (OES) texture. */
    abstract val external: Boolean

    /** The GL texture, 0 until attached. */
    var texture = 0
        protected set

    /** Maps the mesh's (u, v) to the texture's. */
    val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** What the HUD calls it. */
    @Volatile var label: String = name

    /** Its card's picture (an app's icon), or null. */
    @Volatile var icon: Bitmap? = null

    /** Why the screen shows its card instead of its frames (its app closed, say), or null. */
    @Volatile var note: String? = null

    /** The renderer's frame number of the last [update], so a source shared by two screens updates once a frame. */
    @JvmField var stamp = 0L

    /** Something worth seeing yet (a first frame arrived). */
    @Volatile var live = false
        protected set

    abstract fun attach()

    /** Picks up a new frame if there is one. */
    open fun update() {}

    abstract fun detach()
}

/**
 * A source fed by another producer (a decoder, a virtual display) through a Surface of its own. The Surface exists
 * from [attach] on; [onSurface] hears about it, on the GL thread.
 */
abstract class SurfaceSource(name: String, private val width: Int, private val height: Int) : Source(name) {
    override val external = true
    private var surfaceTexture: SurfaceTexture? = null
    /** Frames queued and not yet taken: each [SurfaceTexture.updateTexImage] takes one. */
    private val pending = AtomicInteger()
    /** The texture transform last logged. */
    private val logged = FloatArray(16)

    /** The Surface to draw into, null before [attach] and after [detach]. */
    @Volatile var surface: Surface? = null
        private set

    override fun attach() {
        // Attached again after the GL context was lost: the old SurfaceTexture belongs to a dead context.
        surface?.release()
        surfaceTexture?.release()
        texture = Gl.textureExternal()
        pending.set(0)
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        // Not on the main thread (the GL thread has no Looper, so that's where it would land): a busy main thread would
        // freeze every laptop and app screen.
        st.setOnFrameAvailableListener({ pending.incrementAndGet() }, frameHandler)
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        onSurface(s)
    }

    /** The Surface is ready (GL thread): start the producer. */
    protected abstract fun onSurface(surface: Surface)

    /** The Surface is going away (GL thread): stop the producer first. */
    protected abstract fun onSurfaceGone()

    override fun update() {
        // Every queued frame is taken, the newest shown: one left in the queue would hold its buffer, and with the
        // queue full the producer (the decoder, the app) stops for good.
        var n = pending.getAndSet(0)
        if (n == 0) return
        val st = surfaceTexture ?: return
        while (n-- > 0) st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        if (!texMatrix.contentEquals(logged)) {
            // The producer's buffers set it (a decoder's crop, say): logged when it changes.
            texMatrix.copyInto(logged)
            AppLog.i("$name: texture transform " + texMatrix.joinToString(" ") { String.format(java.util.Locale.US, "%.4f", it) })
        }
        live = true
    }

    override fun detach() {
        onSurfaceGone()
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        Gl.deleteTexture(texture)
        texture = 0
        live = false
    }

    companion object {
        /** Where every SurfaceTexture hears of new frames. */
        private val frameHandler: Handler by lazy {
            Handler(HandlerThread("stationar-frames").apply { start() }.looper)
        }
    }
}
