package io.github.tuthan.stationar.core.math

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class QuatTest {
    private val eps = 1e-5f

    private fun assertVec(x: Float, y: Float, z: Float, v: Vec3) {
        assertEquals(x, v.x, eps, "x of $v")
        assertEquals(y, v.y, eps, "y of $v")
        assertEquals(z, v.z, eps, "z of $v")
    }

    @Test
    fun identityLeavesVectorsAlone() {
        val v = Quat().rotate(Vec3(1f, 2f, 3f), Vec3())
        assertVec(1f, 2f, 3f, v)
    }

    @Test
    fun quarterTurnAboutZ() {
        val q = Quat().setAxisAngle(0f, 0f, 1f, (PI / 2).toFloat())
        assertVec(0f, 1f, 0f, q.rotate(Vec3(1f, 0f, 0f), Vec3()))
        assertVec(-1f, 0f, 0f, q.rotate(Vec3(0f, 1f, 0f), Vec3()))
    }

    @Test
    fun axisNeedNotBeUnitLength() {
        val a = Quat().setAxisAngle(0f, 5f, 0f, 1f)
        val b = Quat().setAxisAngle(0f, 1f, 0f, 1f)
        assertEquals(1f, a.dot(b), eps)
    }

    @Test
    fun multiplyAppliesTheRightOperandFirst() {
        val aboutZ = Quat().setAxisAngle(0f, 0f, 1f, (PI / 2).toFloat())
        val aboutX = Quat().setAxisAngle(1f, 0f, 0f, (PI / 2).toFloat())
        // aboutZ * aboutX: X first takes +Y to +Z, then Z leaves +Z alone.
        val q = Quat().multiply(aboutZ, aboutX)
        assertVec(0f, 0f, 1f, q.rotate(Vec3(0f, 1f, 0f), Vec3()))
    }

    @Test
    fun multiplyIntoAnOperand() {
        val a = Quat().setAxisAngle(0f, 1f, 0f, 0.3f)
        val b = Quat().setAxisAngle(1f, 0f, 0f, 0.7f)
        val expected = Quat().multiply(a, b)
        a.multiply(a, b)
        assertEquals(1f, a.dot(expected), eps)
    }

    @Test
    fun conjugateUndoes() {
        val q = Quat().setAxisAngle(1f, 2f, 3f, 1.2f)
        val back = Quat().multiply(q, Quat().set(q).conjugate())
        assertEquals(1f, back.w, eps)
        assertEquals(0f, back.x, eps)
    }

    @Test
    fun matrixMatchesRotate() {
        val q = Quat().setAxisAngle(0.3f, -1f, 0.5f, 2.1f)
        val m = FloatArray(16)
        q.toMatrix(m)
        val v = Vec3(0.2f, -0.7f, 1.3f)
        val byQuat = q.rotate(v, Vec3())
        val byMatrix = Mat4.transformPoint(m, v.x, v.y, v.z, Vec3())
        assertVec(byQuat.x, byQuat.y, byQuat.z, byMatrix)
    }

    @Test
    fun matrixOfAQuarterTurnAboutYByHand() {
        val m = FloatArray(16)
        Quat().setAxisAngle(0f, 1f, 0f, (PI / 2).toFloat()).toMatrix(m)
        // Column-major: +X goes to -Z, +Z goes to +X.
        val expected = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        for (i in 0 until 16) assertEquals(expected[i], m[i], eps, "m[$i]")
    }

    @Test
    fun integrateAConstantRateIsARotation() {
        val q = Quat()
        // 90°/s about Z for one second, in 1000 steps.
        repeat(1000) { q.integrate(0f, 0f, (PI / 2).toFloat(), 0.001f) }
        val expected = Quat().setAxisAngle(0f, 0f, 1f, (PI / 2).toFloat())
        assertEquals(1f, kotlin.math.abs(q.dot(expected)), 1e-4f)
    }

    @Test
    fun integrateIsInTheBodyFrame() {
        // Turned 90° about Z, then a rate about the body's X: the world sees it about Y.
        val q = Quat().setAxisAngle(0f, 0f, 1f, (PI / 2).toFloat())
        q.integrate(1f, 0f, 0f, (PI / 2).toFloat())
        val expected = Quat().multiply(
            Quat().setAxisAngle(0f, 0f, 1f, (PI / 2).toFloat()),
            Quat().setAxisAngle(1f, 0f, 0f, (PI / 2).toFloat()),
        )
        assertEquals(1f, kotlin.math.abs(q.dot(expected)), 1e-4f)
    }
}
