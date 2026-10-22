package io.github.tuthan.stationar.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.tuthan.stationar.AppLog

/**
 * The glasses' head tracking: Rokid's hardware Game RotationVector (no compass, so nearby metal doesn't swing the
 * view) and the gyroscope for prediction, at the fastest rate, on a thread of their own. Never the glasses' USB HID:
 * Rokid's sensor HAL owns it.
 *
 * The render thread reads the latest sample with [read]; nothing is allocated per event.
 */
class SensorSource(private val sensors: SensorManager) : SensorEventListener {
    /** The latest sample, as the render thread copies it. */
    class Sample {
        @JvmField var w = 1f
        @JvmField var x = 0f
        @JvmField var y = 0f
        @JvmField var z = 0f
        @JvmField var gx = 0f
        @JvmField var gy = 0f
        @JvmField var gz = 0f
        /**
         * When the rotation sample arrived (elapsedRealtimeNanos). Not SensorEvent.timestamp: Rokid's HAL stamps
         * events with the glasses' own clock (about 65 s against the Station's 89,500 s on 2026-10-22).
         */
        @JvmField var timeNs = 0L
        /** Rotation samples so far; 0 until the first arrives. */
        @JvmField var count = 0L
    }

    private val lock = Any()
    private val latest = Sample()
    private var thread: HandlerThread? = null
    val rotation: Sensor? = find(ROTATION_NAME, Sensor.TYPE_GAME_ROTATION_VECTOR)
    val gyroscope: Sensor? = find(GYROSCOPE_NAME, Sensor.TYPE_GYROSCOPE)

    /** True when the glasses' rotation sensor is there (glasses plugged in, Rokid's HAL up). */
    val available: Boolean get() = rotation != null

    private fun find(name: String, type: Int): Sensor? =
        sensors.getSensorList(type).firstOrNull { it.name == name } ?: sensors.getDefaultSensor(type)

    fun start() {
        if (thread != null || rotation == null) return
        val t = HandlerThread("StationAR sensors", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_FASTEST, h)
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        AppLog.i("sensors: ${rotation.name}" + (gyroscope?.let { " + ${it.name}" } ?: " (no gyroscope)"))
    }

    fun stop() {
        sensors.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    /** Copies the latest sample into `out`. */
    fun read(out: Sample) {
        synchronized(lock) {
            out.w = latest.w
            out.x = latest.x
            out.y = latest.y
            out.z = latest.z
            out.gx = latest.gx
            out.gy = latest.gy
            out.gz = latest.gz
            out.timeNs = latest.timeNs
            out.count = latest.count
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        synchronized(lock) {
            if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                latest.gx = v[0]
                latest.gy = v[1]
                latest.gz = v[2]
            } else {
                // Android's rotation vector: x, y, z, then w (cos of half the angle).
                latest.x = v[0]
                latest.y = v[1]
                latest.z = v[2]
                latest.w = if (v.size > 3) v[3] else 0f
                latest.timeNs = SystemClock.elapsedRealtimeNanos()
                latest.count++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        const val ROTATION_NAME = "Rokid Glass Game RotationVector"
        const val GYROSCOPE_NAME = "Rokid Glass Gyroscope"

        /** How old a sample is now, in seconds, from its arrival. */
        fun ageSeconds(timeNs: Long): Float = ((SystemClock.elapsedRealtimeNanos() - timeNs) / 1e9).toFloat()
    }
}
