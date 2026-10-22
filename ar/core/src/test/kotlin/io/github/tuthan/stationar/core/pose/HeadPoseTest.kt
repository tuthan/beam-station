package io.github.tuthan.stationar.core.pose

import io.github.tuthan.stationar.core.math.Mat4
import io.github.tuthan.stationar.core.math.Quat
import io.github.tuthan.stationar.core.math.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadPoseTest {
    /** A phone-like frame: the head looks along device +Y with device +Z up (+90° about X). */
    private val lookAlongY = Quat().setAxisAngle(1f, 0f, 0f, (PI / 2).toFloat())

    private fun rad(deg: Float) = (deg * PI / 180).toFloat()

    private fun HeadPose.feed(q: Quat, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f, age: Float = 0f) =
        update(q.w, q.x, q.y, q.z, gx, gy, gz, age)

    private fun pose() = HeadPose(lookAlongY).apply { predictSeconds = 0f }

    @Test
    fun aheadIsYawAndPitchZero() {
        val p = pose()
        assertFalse(p.ready)
        p.feed(Quat())
        assertTrue(p.ready)
        assertEquals(0f, p.yawDegrees(), 0.01f)
        assertEquals(0f, p.pitchDegrees(), 0.01f)
        assertEquals(0f, p.rollDegrees(), 0.01f)
    }

    @Test
    fun theFirstSampleRecentres() {
        val p = pose()
        p.feed(Quat().setAxisAngle(0f, 0f, 1f, rad(70f)))
        assertEquals(0f, p.yawDegrees(), 0.01f)
    }

    @Test
    fun turningRightIsPositiveYaw() {
        val p = pose()
        p.feed(Quat())
        // Turning right is clockwise seen from above: negative about the sensor world's up (Z).
        p.feed(Quat().setAxisAngle(0f, 0f, 1f, rad(-30f)))
        assertEquals(30f, p.yawDegrees(), 0.05f)
        assertEquals(0f, p.pitchDegrees(), 0.05f)
    }

    @Test
    fun lookingUpIsPositivePitch() {
        val p = pose()
        p.feed(Quat())
        p.feed(Quat().setAxisAngle(1f, 0f, 0f, rad(20f)))
        assertEquals(20f, p.pitchDegrees(), 0.05f)
        assertEquals(0f, p.yawDegrees(), 0.05f)
    }

    @Test
    fun tiltingToTheRightShoulderIsPositiveRoll() {
        val p = pose()
        p.feed(Quat())
        // The head tilts right: a turn about the line of sight (device +Y), clockwise seen from behind.
        p.feed(Quat().setAxisAngle(0f, 1f, 0f, rad(15f)))
        assertEquals(15f, p.rollDegrees(), 0.05f)
    }

    @Test
    fun recentreZeroesTheYawAndKeepsThePitch() {
        val p = pose()
        p.feed(Quat())
        val turned = Quat().multiply(
            Quat().setAxisAngle(0f, 0f, 1f, rad(-50f)),
            Quat().setAxisAngle(1f, 0f, 0f, rad(10f)),
        )
        p.feed(turned)
        assertEquals(50f, p.yawDegrees(), 0.05f)
        p.recentre()
        assertEquals(0f, p.yawDegrees(), 0.05f)
        assertEquals(10f, p.pitchDegrees(), 0.05f)
        // And it holds for later samples.
        p.feed(turned)
        assertEquals(0f, p.yawDegrees(), 0.05f)
    }

    @Test
    fun recentreBeforeAnySampleWaitsForOne() {
        val p = pose()
        p.recentre()
        p.feed(Quat().setAxisAngle(0f, 0f, 1f, rad(40f)))
        assertEquals(0f, p.yawDegrees(), 0.05f)
    }

    @Test
    fun predictionTurnsOnByTheGyroscope() {
        val p = HeadPose(lookAlongY).apply { predictSeconds = 0.05f }
        p.feed(Quat())
        // 1 rad/s to the right (negative about device Z, which is up here), 50 ms ahead plus 10 ms of age.
        p.feed(Quat(), gz = -1f, age = 0.01f)
        assertEquals(Math.toDegrees(0.06).toFloat(), p.yawDegrees(), 0.05f)
    }

    @Test
    fun predictionIsCapped() {
        val p = HeadPose(lookAlongY).apply { predictSeconds = 0.05f }
        p.feed(Quat())
        p.feed(Quat(), gz = -1f, age = 5f)
        assertEquals(Math.toDegrees(0.1).toFloat(), p.yawDegrees(), 0.05f)
    }

    @Test
    fun viewMatrixMovesTheWorldTheOtherWay() {
        val p = pose()
        p.feed(Quat())
        p.feed(Quat().setAxisAngle(0f, 0f, 1f, rad(-30f)))
        val view = FloatArray(16)
        p.viewMatrix(view)
        // A point straight ahead in the world now sits to the left of the view.
        val seen = Mat4.transformPoint(view, 0f, 0f, -1f, Vec3())
        assertTrue(seen.x < -0.4f, "seen at $seen")
        assertEquals(-Math.cos(rad(30f).toDouble()).toFloat(), seen.z, 1e-3f)
    }

    @Test
    fun axisCalibrationRecoversASignedPermutation() {
        // Glasses whose sensor has +X ahead, +Z up and +Y to the left: head X (right) = -Y, head Y = +Z, head Z = -X.
        val truth = AxisCalibration.fromColumns(Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(-1f, 0f, 0f))
        val up = truth.rotate(Vec3(0f, 1f, 0f), Vec3())
        // Looking up 30°: a turn about the head's +X, seen in the device frame.
        val headTurn = Quat().setAxisAngle(1f, 0f, 0f, rad(30f))
        val lookUp = Quat().multiply(Quat().multiply(truth, headTurn), Quat().set(truth).conjugate())
        val found = assertNotNull(AxisCalibration.deviceFromHead(Vec3(up.x * 9.8f, up.y * 9.8f + 0.3f, up.z * 9.8f), lookUp))
        assertEquals(1f, kotlin.math.abs(found.dot(truth)), 1e-4f)
    }

    @Test
    fun axisCalibrationFromTwoRotationSamples() {
        // The same glasses as above, looking ahead with some yaw in the sensor world, then looking up 25°.
        val truth = AxisCalibration.fromColumns(Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(-1f, 0f, 0f))
        // Looking ahead: the head's axes, in the sensor world, are X east-ish, Y up, Z back; sensor = world * head * truth^-1.
        val headAhead = Quat().multiply(Quat().setAxisAngle(0f, 0f, 1f, rad(37f)), lookAlongY)
        val ahead = Quat().multiply(headAhead, Quat().set(truth).conjugate())
        val headUp = Quat().multiply(headAhead, Quat().setAxisAngle(1f, 0f, 0f, rad(25f)))
        val up = Quat().multiply(headUp, Quat().set(truth).conjugate())
        val found = assertNotNull(AxisCalibration.fromRotations(ahead, up))
        assertEquals(1f, kotlin.math.abs(found.dot(truth)), 1e-4f)
        // And with the found axes, HeadPose sees the second pose as 25° up.
        val p = HeadPose(found).apply { predictSeconds = 0f }
        p.feed(ahead)
        p.feed(up)
        assertEquals(25f, p.pitchDegrees(), 0.1f)
        assertEquals(0f, p.yawDegrees(), 0.1f)
    }

    @Test
    fun axisCalibrationNeedsARealTurn() {
        val ahead = Quat().setAxisAngle(1f, 0f, 0f, rad(90f))
        val barely = Quat().multiply(ahead, Quat().setAxisAngle(1f, 0f, 0f, rad(5f)))
        assertNull(AxisCalibration.fromRotations(ahead, barely))
    }

    @Test
    fun axisCalibrationRefusesAxesThatArentSquare() {
        val up = Vec3(1f, 1f, 0f)
        assertEquals(null, AxisCalibration.deviceFromHead(up, Quat().setAxisAngle(0f, 0f, 1f, 0.5f)))
    }
}
