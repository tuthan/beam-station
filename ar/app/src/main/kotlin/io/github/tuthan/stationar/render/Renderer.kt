package io.github.tuthan.stationar.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import io.github.tuthan.stationar.AppLog
import io.github.tuthan.stationar.core.math.Mat4
import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.pose.HeadPose
import io.github.tuthan.stationar.core.scene.Focus
import io.github.tuthan.stationar.core.scene.Layout
import io.github.tuthan.stationar.core.scene.ScreenSpec
import io.github.tuthan.stationar.sensors.SensorSource
import io.github.tuthan.stationar.sources.Source
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the room: every screen where the layout puts it, seen from where your head points, plus the panel and the
 * HUD. Runs on the GL thread; everything else talks to it through `GLSurfaceView.queueEvent`. [onDrawFrame] allocates
 * nothing.
 *
 * Without glasses (no rotation samples), the view turns with [turn] instead, so AR can be tried on a monitor.
 */
class Renderer(
    private val sensors: SensorSource,
    deviceFromHead: Quat,
    private val listener: Listener,
) : GLSurfaceView.Renderer {
    interface Listener {
        /** The focused screen changed (index in the layout, or -1), on the GL thread. */
        fun onFocus(index: Int)

        /** Head tracking came or went, on the GL thread. */
        fun onTracking(tracking: Boolean)
    }

    /** A screen as drawn: its spec, what it shows, its mesh and where it is. */
    private class Slot(val spec: ScreenSpec, val source: Source?, val u0: Float, val u1: Float) {
        val mesh = ScreenMesh()
        val frame = ScreenMesh()
        val model = FloatArray(16)
        var placeholder = 0
    }

    val pose = HeadPose(deviceFromHead)
    private val focus = Focus()
    private val sample = SensorSource.Sample()
    private var lastSample = 0L
    private var lastSampleAt = 0L
    private var tracking = false

    private var programs: Programs? = null
    private val slots = ArrayList<Slot>()
    private val specs = ArrayList<ScreenSpec>()
    private val sources = HashMap<String, Source>()
    private var frameNumber = 0L
    private var sizeScale = 1f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val headQuat = Quat()

    // Without glasses: the view's own yaw and pitch, turned by the D-pad.
    @Volatile private var manualYaw = 0f
    @Volatile private var manualPitch = 0f

    private val hud = Hud()
    private val hudMesh = ScreenMesh()
    private val hudModel = FloatArray(16)
    @Volatile private var hudLine = ""

    private val panelView = PanelView()
    private val panelMesh = ScreenMesh()
    private val panelModel = FloatArray(16)
    private var panelOpen = false
    private var panelYaw = 0f
    private var panelPitch = 0f

    // Frame statistics, logged every 10 s.
    private val intervals = LongArray(600)
    private val draws = LongArray(600)
    private var stats = 0
    private var lastFrameAt = 0L
    private var statsSince = 0L

    /** The gaze now (degrees), for the panel and the controller. */
    @Volatile var gazeYaw = 0f
        private set
    @Volatile var gazePitch = 0f
        private set

    /** True while rotation samples arrive. */
    val headTracked: Boolean get() = tracking

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programs = Programs()
        // A new GL context: every texture is gone, so sources and pictures are made again.
        for (s in sources.values) s.attach()
        for (slot in slots) slot.placeholder = 0
        hud.release()
        panelView.release()
        hudLine.let { hud.set(it) }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        AppLog.i("GL: " + GLES20.glGetString(GLES20.GL_RENDERER) + ", " + GLES20.glGetString(GLES20.GL_VERSION))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Mat4.perspective(projection, FOV_Y, width.toFloat() / height, 0.1f, 50f)
        hudMesh.build(HUD_WIDTH, Hud.W.toFloat() / Hud.H, false)
        AppLog.i("surface ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        val start = SystemClock.elapsedRealtimeNanos()
        frameNumber++
        val p = programs ?: return
        updatePose(start)
        Mat4.multiply(viewProjection, projection, view)
        if (focus.update(specs, gazeYaw, gazePitch, start / 1_000_000)) listener.onFocus(focus.focused)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        for (i in 0 until slots.size) {
            val slot = slots[i]
            Mat4.multiply(mvp, viewProjection, slot.model)
            if (i == focus.focused && slots.size > 1) {
                p.color.draw(slot.frame.vertices, slot.frame.count, mvp, 0.37f, 0.92f, 0.83f, 0.9f)
            }
            val source = slot.source
            if (source != null && source.stamp != frameNumber) {
                source.update()
                source.stamp = frameNumber
            }
            if (source != null && source.live) {
                val prog = if (source.external) p.external else p.bitmap
                prog.draw(slot.mesh.vertices, slot.mesh.count, mvp, source.texture, source.texMatrix, slot.u0, 0f, slot.u1, 1f, 1f)
            } else {
                if (slot.placeholder == 0) slot.placeholder = placeholder(slot)
                p.bitmap.draw(slot.mesh.vertices, slot.mesh.count, mvp, slot.placeholder, Gl.FLIP_V, 0f, 0f, 1f, 1f, 1f)
            }
        }
        if (panelOpen && panelView.texture != 0) {
            Mat4.multiply(mvp, viewProjection, panelModel)
            p.bitmap.draw(panelMesh.vertices, panelMesh.count, mvp, panelView.texture, Gl.FLIP_V, 0f, 0f, 1f, 1f, 1f)
        }
        if (hud.visible && !panelOpen) {
            // Head-locked: no view matrix.
            Mat4.multiply(mvp, projection, hudModel)
            p.bitmap.draw(hudMesh.vertices, hudMesh.count, mvp, hud.texture, Gl.FLIP_V, 0f, 0f, 1f, 1f, 0.95f)
        }
        frameStats(start)
    }

    private fun updatePose(now: Long) {
        sensors.read(sample)
        if (sample.count != lastSample) {
            lastSample = sample.count
            lastSampleAt = now
        }
        val fresh = sample.count > 0 && now - lastSampleAt < 500_000_000L
        if (fresh != tracking) {
            tracking = fresh
            if (fresh) pose.recentre()
            listener.onTracking(fresh)
        }
        if (fresh) {
            pose.update(sample.w, sample.x, sample.y, sample.z, sample.gx, sample.gy, sample.gz,
                SensorSource.ageSeconds(sample.timeNs))
            pose.viewMatrix(view)
            gazeYaw = pose.yawDegrees()
            gazePitch = pose.pitchDegrees()
        } else {
            // view = inverse(Ry(-yaw) * Rx(pitch)) = Rx(-pitch) * Ry(yaw)
            Mat4.setRotationX(tmpA, -manualPitch)
            Mat4.setRotationY(tmpB, manualYaw)
            Mat4.multiply(view, tmpA, tmpB)
            gazeYaw = manualYaw
            gazePitch = manualPitch
        }
    }

    private fun frameStats(start: Long) {
        val end = SystemClock.elapsedRealtimeNanos()
        if (lastFrameAt != 0L && stats < intervals.size) {
            intervals[stats] = start - lastFrameAt
            draws[stats] = end - start
            stats++
        }
        lastFrameAt = start
        if (statsSince == 0L) statsSince = start
        if (start - statsSince >= 10_000_000_000L) {
            if (stats > 10) {
                java.util.Arrays.sort(intervals, 0, stats)
                java.util.Arrays.sort(draws, 0, stats)
                val fps = stats * 1e9 / (start - statsSince)
                AppLog.i(String.format(java.util.Locale.US, "frames: %.1f fps, interval p50 %.1f p95 %.1f ms, draw p50 %.1f p95 %.1f ms, %d screens, tracking %b",
                    fps, intervals[stats / 2] / 1e6, intervals[stats * 95 / 100] / 1e6, draws[stats / 2] / 1e6, draws[stats * 95 / 100] / 1e6, slots.size, tracking))
            }
            stats = 0
            statsSince = start
        }
    }

    // MARK: - Calls from the controller (through queueEvent, so on the GL thread)

    /**
     * Shows a layout. [sourceFor] gives each screen's source by its name (attached here if new); sources no screen
     * uses any more are detached.
     */
    fun setLayout(layout: Layout, sourceFor: (String) -> Source?) {
        val wanted = HashMap<String, Source>()
        for (spec in layout.screens) {
            val name = sourceName(spec.source)
            val s = sources[name] ?: sourceFor(name)?.also { if (programs != null) it.attach() }
            if (s != null) wanted[name] = s
        }
        for ((name, s) in sources) if (wanted[name] !== s) s.detach()
        sources.clear()
        sources.putAll(wanted)
        for (slot in slots) Gl.deleteTexture(slot.placeholder)
        slots.clear()
        specs.clear()
        for (spec in layout.screens) {
            val s = sources[sourceName(spec.source)]
            val slot = Slot(spec.copy(), s, spec.u0, spec.u1)
            build(slot)
            slots.add(slot)
            specs.add(slot.spec)
        }
        focus.reset()
    }

    /** The source a screen's name draws from: both halves of a wide stream share one. */
    private fun sourceName(source: String) = if (source.startsWith("wide:")) "wide" else source

    private fun build(slot: Slot) {
        val s = slot.spec
        slot.mesh.build(s.width * sizeScale, s.aspect, s.curved)
        slot.frame.build(s.width * sizeScale, s.aspect, s.curved, marginDeg = 0.6f)
        // model = Ry(-yaw) * Rx(pitch): pitch it up first, then turn it to its yaw (right is negative about Y).
        Mat4.setRotationY(tmpA, -s.yaw)
        Mat4.setRotationX(tmpB, s.pitch)
        Mat4.multiply(slot.model, tmpA, tmpB)
        Gl.deleteTexture(slot.placeholder)
        slot.placeholder = 0
    }

    /** A screen whose source shows nothing yet: its name and what it's waiting for. */
    private fun placeholder(slot: Slot): Int {
        val b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(22, 30, 38))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText(slot.source?.label ?: slot.spec.source, 480f, 250f, paint)
        paint.textSize = 28f
        paint.color = Color.rgb(150, 180, 195)
        c.drawText(waiting(slot.spec), 480f, 310f, paint)
        val t = Gl.texture2d(b)
        b.recycle()
        return t
    }

    private fun waiting(spec: ScreenSpec) = when (spec.kind) {
        "laptop", "wide" -> "Waiting for the laptop (station-ar-send)"
        "app" -> "Opening…"
        else -> ""
    }

    /** Re-centre: what you look at now becomes straight ahead. */
    fun recentre() {
        if (tracking) pose.recentre() else {
            manualYaw = 0f
            manualPitch = 0f
        }
    }

    /** Without glasses: turns the view (degrees). */
    fun turn(dYaw: Float, dPitch: Float) {
        manualYaw = Focus.angle(manualYaw + dYaw)
        manualPitch = (manualPitch + dPitch).coerceIn(-60f, 60f)
    }

    /** Moves screen `index` by dYaw degrees. */
    fun nudge(index: Int, dYaw: Float, dPitch: Float) {
        val slot = slots.getOrNull(index) ?: return
        slot.spec.yaw = Focus.angle(slot.spec.yaw + dYaw)
        slot.spec.pitch = (slot.spec.pitch + dPitch).coerceIn(-60f, 60f)
        build(slot)
    }

    /** Scales every screen (the screen-size setting, 0.8 to 1.2). */
    fun setSizeScale(scale: Float) {
        sizeScale = scale
        for (slot in slots) build(slot)
    }

    /** The specs as drawn now (after nudges), for saving. */
    fun currentLayout(): Layout = Layout(slots.mapTo(ArrayList()) { it.spec.copy() })

    /** Focuses a screen at once (one just opened). */
    fun focusNow(index: Int) {
        focus.set(index)
        listener.onFocus(index)
    }

    val focused: Int get() = focus.focused

    fun setHud(line: String) {
        hudLine = line
        hud.set(line)
        // Head-locked near the top of the view.
        Mat4.setRotationX(hudModel, HUD_PITCH)
    }

    /** Opens the panel where you look (or redraws it if open). */
    fun showPanel(draw: (PanelView) -> Unit, aspectWidth: Float = PANEL_WIDTH) {
        if (!panelOpen) {
            panelOpen = true
            panelYaw = gazeYaw
            panelPitch = gazePitch.coerceIn(-15f, 15f)
            panelMesh.build(aspectWidth, PanelView.W.toFloat() / PanelView.H, false)
            Mat4.setRotationY(tmpA, -panelYaw)
            Mat4.setRotationX(tmpB, panelPitch)
            Mat4.multiply(panelModel, tmpA, tmpB)
        }
        draw(panelView)
    }

    fun hidePanel() {
        panelOpen = false
    }

    /** Releases every source and texture (the activity is leaving). */
    fun release() {
        for (s in sources.values) s.detach()
        sources.clear()
        for (slot in slots) Gl.deleteTexture(slot.placeholder)
        slots.clear()
        specs.clear()
        hud.release()
        panelView.release()
    }

    /** The orientation of the head now (for tests and the log). */
    fun head(): Quat = pose.orientation(headQuat)

    companion object {
        /** The Rokid Max: about 50° diagonal, so 25.7° high for 16:9. */
        const val FOV_Y = 25.7f
        const val HUD_WIDTH = 22f
        const val HUD_PITCH = 10.6f
        const val PANEL_WIDTH = 32f
    }
}
