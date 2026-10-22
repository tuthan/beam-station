package io.github.tuthan.stationar.core.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Mat4Test {
    private val eps = 1e-5f

    private fun assertVec(x: Float, y: Float, z: Float, v: Vec3) {
        assertEquals(x, v.x, eps, "x of $v")
        assertEquals(y, v.y, eps, "y of $v")
        assertEquals(z, v.z, eps, "z of $v")
    }

    @Test
    fun identityTimesAnything() {
        val i = FloatArray(16).also { Mat4.identity(it) }
        val r = FloatArray(16).also { Mat4.setRotationY(it, 33f) }
        val out = FloatArray(16)
        Mat4.multiply(out, i, r)
        for (k in 0 until 16) assertEquals(r[k], out[k], eps)
    }

    @Test
    fun multiplyAppliesTheRightOperandFirst() {
        val t = FloatArray(16).also { Mat4.setTranslation(it, 0f, 0f, -2f) }
        val r = FloatArray(16).also { Mat4.setRotationY(it, 90f) }
        val out = FloatArray(16)
        Mat4.multiply(out, r, t)
        // Moved to z = -2 first, then turned 90° left: it ends up at x = -2.
        assertVec(-2f, 0f, 0f, Mat4.transformPoint(out, 0f, 0f, 0f, Vec3()))
    }

    @Test
    fun multiplyRefusesAliasing() {
        val a = FloatArray(16).also { Mat4.identity(it) }
        assertFailsWith<IllegalArgumentException> { Mat4.multiply(a, a, FloatArray(16)) }
    }

    @Test
    fun rotationXTurnsForwardUp() {
        val r = FloatArray(16).also { Mat4.setRotationX(it, 90f) }
        assertVec(0f, 1f, 0f, Mat4.transformPoint(r, 0f, 0f, -1f, Vec3()))
    }

    @Test
    fun rotationYMatchesTheQuaternion() {
        val byMat = FloatArray(16).also { Mat4.setRotationY(it, 40f) }
        val byQuat = FloatArray(16)
        Quat().setAxisAngle(0f, 1f, 0f, Math.toRadians(40.0).toFloat()).toMatrix(byQuat)
        for (k in 0 until 16) assertEquals(byQuat[k], byMat[k], eps, "m[$k]")
    }

    @Test
    fun invertRigidUndoes() {
        val r = FloatArray(16).also { Mat4.setRotationY(it, 25f) }
        val t = FloatArray(16).also { Mat4.setTranslation(it, 1f, 2f, 3f) }
        val m = FloatArray(16).also { Mat4.multiply(it, t, r) }
        val inv = FloatArray(16).also { Mat4.invertRigid(it, m) }
        val back = FloatArray(16).also { Mat4.multiply(it, inv, m) }
        val i = FloatArray(16).also { Mat4.identity(it) }
        for (k in 0 until 16) assertEquals(i[k], back[k], eps, "m[$k]")
    }

    @Test
    fun perspectiveMapsTheFrustumEdges() {
        val p = FloatArray(16).also { Mat4.perspective(it, 90f, 2f, 0.1f, 10f) }
        // A 90° vertical field: y = -z at the top edge; aspect 2: x = -2z at the right edge; far maps to +1.
        assertVec(1f, 1f, 1f, Mat4.transformPoint(p, 20f, 10f, -10f, Vec3()))
        assertEquals(-1f, Mat4.transformPoint(p, 0f, 0f, -0.1f, Vec3()).z, 1e-4f)
    }
}
