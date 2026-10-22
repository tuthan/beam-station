package io.github.tuthan.stationar

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.tuthan.stationar.adb.AdbClient
import io.github.tuthan.stationar.apps.AppScreens
import io.github.tuthan.stationar.core.pose.AxisCalibration
import io.github.tuthan.stationar.core.pose.HeadPose
import io.github.tuthan.stationar.glasses.GlassesSession
import io.github.tuthan.stationar.receiver.Tls
import io.github.tuthan.stationar.sensors.SensorSource
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Station AR's 2D screen: whether this box can do AR, the head tracking live (rate, yaw, pitch, roll and drift), and
 * the way into AR. Plain Views, driven by the remote.
 */
class SetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var sensors: SensorSource
    private lateinit var status: TextView
    private lateinit var tracking: TextView
    private lateinit var laptops: TextView
    private lateinit var appsLine: TextView
    private val sample = SensorSource.Sample()
    private var pose = HeadPose()
    private var lastCount = 0L
    private var lastAt = 0L
    private var firstYaw = Float.NaN
    private var firstAt = 0L
    private var rate = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = SensorSource(getSystemService(SensorManager::class.java))
        val glasses = GlassesSession(this)
        // A crash in AR may have left the glasses at AR's refresh rate, or the key filter switched on.
        work.execute {
            glasses.restoreLeftover()
            AppScreens.cleanLeftover(this)
        }
        setContentView(build())
    }

    private fun build(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(40), dp(56), dp(40))
            setBackgroundColor(Color.rgb(14, 20, 26))
        }
        column.addView(text("Station AR", 34f, Color.WHITE, bold = true))
        column.addView(text("Screens that stay put in the room as you turn your head.", 18f, Color.rgb(150, 180, 195)))
        status = text("", 18f, Color.rgb(220, 230, 235)).also { it.setPadding(0, dp(24), 0, dp(8)) }
        column.addView(status)
        tracking = text("", 18f, Color.rgb(94, 234, 212)).also { it.typeface = Typeface.MONOSPACE }
        column.addView(tracking)
        laptops = text("", 18f, Color.rgb(220, 230, 235)).also { it.setPadding(0, dp(16), 0, 0) }
        column.addView(laptops)
        appsLine = text("App screens use the Station's own USB debugging: allow it once, here or the first time you " +
            "open an app in AR.", 18f, Color.rgb(220, 230, 235)).also { it.setPadding(0, dp(16), 0, 0) }
        column.addView(appsLine)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(28), 0, 0)
        }
        val open = button("Open AR") { startActivity(Intent(this, ArActivity::class.java)) }
        buttons.addView(open)
        buttons.addView(button("Allow app screens") { allowAdb() })
        buttons.addView(button("Forget laptops") {
            val pairing = StationAr.pairing(this)
            work.execute {
                for (name in pairing.laptops()) pairing.forget(name)
                showLaptops()
            }
        })
        column.addView(buttons)
        open.requestFocus()
        (open.background as GradientDrawable).setColor(Color.rgb(20, 120, 112))
        if (!isRokid()) {
            open.isEnabled = false
            status.text = "Station AR is made for the Rokid Station with its glasses. This is a " +
                "${Build.MANUFACTURER} ${Build.MODEL}: there's nothing for it to do here."
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isRokid()) return
        val axes = StationAr.settings(this).axes
        pose = HeadPose(axes ?: AxisCalibration.DEFAULT).apply { predictSeconds = 0f }
        firstYaw = Float.NaN
        lastCount = 0
        sensors.start()
        tick.run()
        work.execute { showLaptops() }
    }

    /** The paired laptops and the receiver's key (worker thread). */
    private fun showLaptops() {
        val port = StationAr.settings(this).port
        val key = try {
            Tls.shortKey(Tls.fingerprint())
        } catch (e: Exception) {
            AppLog.w("receiver key", e)
            "?"
        }
        val names = StationAr.pairing(this).laptops()
        main.post {
            laptops.text = "Laptops: " + (if (names.isEmpty()) "none paired yet" else names.joinToString(", ")) +
                "\nReceiver: port $port, key $key (open while AR is; station-ar-send pairs a laptop)"
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        sensors.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        work.shutdown()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        val plugged = GlassesSession.plugged(this)
        val axes = StationAr.settings(this).axes
        status.text = buildString {
            append("Rokid Station: yes\n")
            append("Glasses' sensors: ").append(if (plugged) "connected" else "not on the Station's USB (AR still opens: the D-pad turns the view)")
            append('\n')
            append("Head axes: ").append(if (axes == null) "not calibrated yet (AR asks the first time)" else "calibrated")
        }
        sensors.read(sample)
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now > lastAt) rate = (sample.count - lastCount) * 1000f / (now - lastAt)
        lastCount = sample.count
        lastAt = now
        if (sample.count == 0L || rate == 0f) {
            tracking.text = "Head tracking: no samples" + if (sensors.available) " (are the glasses plugged in and on?)" else " (no rotation sensor)"
            return
        }
        pose.update(sample.w, sample.x, sample.y, sample.z, 0f, 0f, 0f, 0f)
        val yaw = pose.yawDegrees()
        if (firstYaw.isNaN()) {
            firstYaw = yaw
            firstAt = now
        }
        val minutes = (now - firstAt) / 60000f
        val drift = yaw - firstYaw
        tracking.text = String.format(Locale.US,
            "Head tracking: %.0f samples/s\nyaw %6.1f°  pitch %6.1f°  roll %6.1f°\ndrift since start %.2f° in %.1f min (%.2f°/min)",
            rate, yaw, pose.pitchDegrees(), pose.rollDegrees(), drift, minutes, if (minutes > 0.05f) drift / minutes else 0f)
    }

    /** Connects to the Station's own ADB once, so it asks "Allow USB debugging?" for Station AR's key now. */
    private fun allowAdb() {
        appsLine.text = "Connecting…"
        work.execute {
            val result = try {
                AdbClient.connect(this, 60_000) {
                    main.post { appsLine.text = "On the screen: tick \"Always allow from this computer\", then OK." }
                }.close()
                "App screens: ready (USB debugging allowed for Station AR)."
            } catch (e: Exception) {
                "App screens: not allowed (${e.message})."
            }
            main.post { appsLine.text = result }
        }
    }

    private fun isRokid() = Build.BRAND.equals("Rokid", ignoreCase = true) && sensors.available

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(14), dp(28), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(30, 41, 50))
        }
        if (isFocused) (background as GradientDrawable).setColor(Color.rgb(20, 120, 112))
        setOnFocusChangeListener { v, focused ->
            (v.background as GradientDrawable).setColor(if (focused) Color.rgb(20, 120, 112) else Color.rgb(30, 41, 50))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(16) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
