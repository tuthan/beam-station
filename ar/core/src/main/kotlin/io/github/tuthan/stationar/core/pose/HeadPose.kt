package io.github.tuthan.stationar.core.pose

import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.math.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * The head's orientation from the glasses' rotation vector, as the renderer and look-to-focus need it.
 *
 * Frames:
 * - **sensor world**: Android's, Z up (a Game RotationVector's yaw is arbitrary);
 * - **device**: the glasses' sensor axes, which the rotation vector maps to the sensor world;
 * - **head**: OpenGL's camera, X right, Y up, looking down -Z; [deviceFromHead] holds the head axes in device
 *   coordinates (Phase 00, test 4);
 * - **world**: the scene's, Y up, -Z ahead of you after a re-centre.
 *
 * Angles for layouts and focus are degrees: yaw positive to the right, pitch positive upwards.
 *
 * Not thread-safe: one thread (the renderer) calls [update], [recentre] and the getters.
 */
class HeadPose(deviceFromHead: Quat = Quat()) {
    /** The head's axes in the device frame: a constant per kind of glasses. */
    val deviceFromHead = Quat().set(deviceFromHead)

    /** How far ahead of the latest sample to predict, from the gyroscope's rate (seconds). */
    var predictSeconds = 0.030f

    private val sensor = Quat()
    private val predicted = Quat()
    private val unrecentred = Quat()
    private val recentre = Quat()
    private val head = Quat()
    private val tmp = Quat()
    private val v = Vec3()
    private var hasSample = false
    private var recentreWanted = true

    /** True once a sample has come in. */
    val ready: Boolean get() = hasSample

    /**
     * A new pose: the rotation vector as a unit quaternion (w, x, y, z), the gyroscope's rates in the device frame
     * (rad/s), and how old the sample is now (seconds; the prediction covers it too). The first sample re-centres.
     */
    fun update(w: Float, x: Float, y: Float, z: Float, gx: Float, gy: Float, gz: Float, ageSeconds: Float) {
        sensor.set(w, x, y, z).normalize()
        val ahead = (ageSeconds.coerceIn(0f, 0.1f) + predictSeconds).coerceIn(0f, 0.1f)
        predicted.set(sensor).integrate(gx, gy, gz, ahead)
        // world (before re-centring) = glFromSensor * sensor * deviceFromHead
        unrecentred.multiply(GL_FROM_SENSOR, predicted).multiply(unrecentred, deviceFromHead)
        hasSample = true
        if (recentreWanted) {
            recentreWanted = false
            recentreNow()
        }
        head.multiply(recentre, unrecentred)
    }

    /** Puts what you look at now straight ahead (yaw 0), keeping your pitch and roll. Applies on the next sample. */
    fun recentre() {
        if (hasSample) {
            recentreNow()
            head.multiply(recentre, unrecentred)
        } else {
            recentreWanted = true
        }
    }

    private fun recentreNow() {
        unrecentred.rotate(v.set(0f, 0f, -1f), v)
        val yawLeft = atan2(-v.x, -v.z)
        recentre.setAxisAngle(0f, 1f, 0f, -yawLeft)
    }

    /** The view matrix (world to head: the inverse of the head's rotation), column-major, into `out`. */
    fun viewMatrix(out: FloatArray) {
        tmp.set(head).conjugate().toMatrix(out)
    }

    /** The head's rotation in the world. */
    fun orientation(out: Quat): Quat = out.set(head)

    /** Where you look: yaw in degrees, positive to the right. */
    fun yawDegrees(): Float {
        head.rotate(v.set(0f, 0f, -1f), v)
        return (atan2(v.x, -v.z) * 180.0 / PI).toFloat()
    }

    /** Where you look: pitch in degrees, positive upwards. */
    fun pitchDegrees(): Float {
        head.rotate(v.set(0f, 0f, -1f), v)
        return (asin(v.y.coerceIn(-1f, 1f)) * 180.0 / PI).toFloat()
    }

    /** The head's roll in degrees, positive when the head tilts to the right shoulder. */
    fun rollDegrees(): Float {
        // The head's up and right axes in the world: roll is how far "right" dips below the horizon.
        head.rotate(v.set(1f, 0f, 0f), v)
        return (-asin(v.y.coerceIn(-1f, 1f)) * 180.0 / PI).toFloat()
    }

    companion object {
        /** The sensor world (Z up, Y ahead) in the scene's (Y up, -Z ahead): -90° about X. */
        @JvmField val GL_FROM_SENSOR: Quat = Quat().setAxisAngle(1f, 0f, 0f, (-PI / 2).toFloat())
    }
}
