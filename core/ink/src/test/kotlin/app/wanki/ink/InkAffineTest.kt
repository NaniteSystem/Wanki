package app.wanki.ink

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkAffineTest {
    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(kotlin.math.abs(expected - actual) <= eps, "expected $expected, got $actual")

    @Test
    fun scaleAboutCentreKeepsCentre() {
        val m = InkAffine.scaleAbout(50f, 80f, 3f)
        val (x, y) = m.apply(50f, 80f)
        assertNear(50f, x); assertNear(80f, y)
        val (x2, y2) = m.apply(60f, 80f)
        assertNear(80f, x2); assertNear(80f, y2)
    }

    @Test
    fun rotate90() {
        val (x, y) = InkAffine.rotateAbout(0f, 0f, (PI / 2).toFloat()).apply(10f, 0f)
        assertNear(0f, x); assertNear(10f, y)
    }

    @Test
    fun brushScalesWithSqrtDet() {
        val s = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 0f, 10)), brush = InkBrush(size = 3f))
        val scaled = InkAffine.scaleAbout(0f, 0f, 2f).apply(s)
        assertNear(6f, scaled.brush.size)
        assertNear(20f, scaled.points[1].x)
        // a turn alone keeps the width, and turns the pen orientation with the stroke
        val turned = InkAffine.rotateAbout(0f, 0f, (PI / 2).toFloat()).apply(s.copy(points = s.points.map { it.copy(orientationRadians = 0.5f) }))
        assertNear(3f, turned.brush.size)
        assertNear(0.5f + (PI / 2).toFloat(), turned.points[0].orientationRadians)
        assertEquals(s.points[1].timeMs, turned.points[1].timeMs)
    }

    @Test
    fun thenComposes() {
        val m = InkAffine.translate(5f, 0f).then(InkAffine.scaleAbout(0f, 0f, 2f)).then(InkAffine.rotateAbout(0f, 0f, (PI / 2).toFloat()))
        // (1, 0) → (6, 0) → (12, 0) → (0, 12)
        val (x, y) = m.apply(1f, 0f)
        assertNear(0f, x); assertNear(12f, y)
        assertEquals(InkAffine.identity, InkAffine.identity.then(InkAffine.identity))
        assertTrue(InkAffine.translate(0f, 0f).isIdentity)
    }
}
