package io.github.tuthan.stationar.sources

import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.view.Surface
import io.github.tuthan.stationar.render.Gl

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
    @Volatile private var frames = 0
    private var seen = 0

    /** The Surface to draw into, null before [attach] and after [detach]. */
    @Volatile var surface: Surface? = null
        private set

    override fun attach() {
        // Attached again after the GL context was lost: the old SurfaceTexture belongs to a dead context.
        surface?.release()
        surfaceTexture?.release()
        texture = Gl.textureExternal()
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener { frames++ }
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
        val f = frames
        if (f == seen) return
        seen = f
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
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
}
