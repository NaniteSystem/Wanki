package app.wanki.ink

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class StraightLineTest {
    private fun moving() = List(7) { InkPoint(it * 10f, it * 3f, it * 50L) } // 300 ms, ~63 units

    @Test
    fun heldAfterHalfSecond() {
        val pts = moving()
        assertTrue(StraightLine.isHeld(pts, 300 + 520, 6f, 24f))
        assertFalse(StraightLine.isHeld(pts, 300 + 400, 6f, 24f))
    }

    @Test
    fun jitterWithinSlop() {
        val last = moving().last()
        val pts = moving() + listOf(
            InkPoint(last.x + 2, last.y - 2, 400), InkPoint(last.x - 2, last.y + 2, 520),
            InkPoint(last.x + 1, last.y + 2, 700), InkPoint(last.x, last.y, 820),
        )
        assertTrue(StraightLine.isHeld(pts, 820, 6f, 24f))
    }

    @Test
    fun shortStrokeNotHeld() {
        val pts = List(3) { InkPoint(it * 3f, 0f, it * 50L) }
        assertFalse(StraightLine.isHeld(pts, 2000, 6f, 24f))
    }

    @Test
    fun lineEndpointsAndSpacing() {
        val stroke = InkStroke(listOf(InkPoint(0f, 0f, 0, 0.4f), InkPoint(20f, 5f, 100, 0.6f)), InkTool.STYLUS)
        val line = StraightLine.line(stroke, InkPoint(100f, 0f, 700))
        assertEquals(14, line.points.size)
        assertEquals(0f, line.points.first().x); assertEquals(0f, line.points.first().y)
        assertEquals(100f, line.points.last().x); assertEquals(0f, line.points.last().y)
        assertEquals(700L, line.points.last().timeMs)
        assertTrue(line.points.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
        assertEquals(0.5f, line.points[3].pressure, 1e-6f)
        assertEquals(InkTool.STYLUS, line.tool)
    }

    @Test
    fun nanPressureStaysNan() {
        val stroke = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(20f, 0f, 100)), InkTool.FINGER)
        assertTrue(StraightLine.line(stroke, InkPoint(50f, 0f, 200)).points.all { it.pressure.isNaN() })
    }
}
