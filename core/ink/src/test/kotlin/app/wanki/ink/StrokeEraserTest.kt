package app.wanki.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrokeEraserTest {
    /** Brush width 0, so the effective radius is exactly the eraser's. */
    private val thin = InkBrush(BrushKind.PEN, 0xFF000000.toInt(), 0f)

    private fun line(vararg xs: Float, y: Float = 0f, brush: InkBrush = thin) = InkStroke(
        xs.mapIndexed { i, x -> InkPoint(x, y, i * 100L, 0.2f + 0.1f * i, 0.5f, 1f) }, InkTool.STYLUS, brush,
    )

    @Test
    fun cutsMiddle() {
        val s = line(0f, 100f)
        val pieces = assertNotNull(StrokeEraser.cut(s, 50f, 0f, 50f, 0f, 5f))
        assertEquals(2, pieces.size)
        assertEquals(45f, pieces[0].points.last().x, 0.5f)
        assertEquals(55f, pieces[1].points.first().x, 0.5f)
        assertEquals(0f, pieces[0].points.first().x)
        assertEquals(100f, pieces[1].points.last().x)
        // interpolated time and pressure at the cut points (a line from t=0 p=0.2 to t=100 p=0.3)
        assertEquals(45L, pieces[0].points.last().timeMs)
        assertEquals(0.245f, pieces[0].points.last().pressure, 0.005f)
        assertEquals(55L, pieces[1].points.first().timeMs)
        assertEquals(0.255f, pieces[1].points.first().pressure, 0.005f)
        assertEquals(s.tool, pieces[0].tool)
        assertEquals(s.brush, pieces[1].brush)
    }

    @Test
    fun fastDragNoGap() {
        // a long step across the line: the capsule, not just its ends, cuts
        val s = line(0f, 100f)
        val pieces = assertNotNull(StrokeEraser.cut(s, 20f, -50f, 80f, 50f, 5f))
        assertEquals(2, pieces.size)
        val gapStart = pieces[0].points.last().x
        val gapEnd = pieces[1].points.first().x
        assertTrue(gapStart < 50f && gapEnd > 50f, "the crossing at x=50 is in the gap: $gapStart..$gapEnd")
        assertTrue(touchesSame(s, 20f, -50f, 80f, 50f, 5f))
    }

    private fun touchesSame(s: InkStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float) =
        StrokeEraser.touches(s, ax, ay, bx, by, r) == (StrokeEraser.cut(s, ax, ay, bx, by, r) != null)

    @Test
    fun eraseEnd() {
        val pieces = assertNotNull(StrokeEraser.cut(line(0f, 100f), 100f, 0f, 100f, 0f, 5f))
        assertEquals(1, pieces.size)
        assertEquals(95f, pieces[0].points.last().x, 0.5f)
        assertEquals(0f, pieces[0].points.first().x)
        val start = assertNotNull(StrokeEraser.cut(line(0f, 100f), 0f, 0f, 0f, 0f, 5f))
        assertEquals(1, start.size)
        assertEquals(5f, start[0].points.first().x, 0.5f)
    }

    @Test
    fun shortPiecesDropped() {
        // brush 4: the eraser (r 5) widens to 7; the piece 0..3 is shorter than the brush and goes
        val brush = InkBrush(BrushKind.PEN, 0xFF000000.toInt(), 4f)
        val s = line(0f, 100f, brush = brush)
        val pieces = assertNotNull(StrokeEraser.cut(s, 10f, 0f, 10f, 0f, 5f))
        assertEquals(1, pieces.size)
        assertEquals(17f, pieces[0].points.first().x, 0.5f)
        // thin brush: the floor is 2
        assertEquals(1, assertNotNull(StrokeEraser.cut(line(0f, 100f), 2.5f, 0f, 2.5f, 0f, 1f)).size)
        assertEquals(2, assertNotNull(StrokeEraser.cut(line(0f, 100f), 4f, 0f, 4f, 0f, 1f)).size)
    }

    @Test
    fun degenerateInputs() {
        val dot = InkStroke(listOf(InkPoint(10f, 10f, 0)), InkTool.FINGER, thin)
        assertEquals(emptyList(), StrokeEraser.cut(dot, 12f, 10f, 12f, 10f, 5f))
        assertNull(StrokeEraser.cut(dot, 50f, 50f, 50f, 50f, 5f))
        assertTrue(StrokeEraser.touches(dot, 12f, 10f, 12f, 10f, 5f))
        assertTrue(!StrokeEraser.touches(dot, 50f, 50f, 50f, 50f, 5f))

        val same = InkStroke(List(3) { InkPoint(10f, 10f, it * 10L) }, InkTool.FINGER, thin)
        val r = StrokeEraser.cut(same, 10f, 10f, 10f, 10f, 5f)
        assertTrue(r == null || r.isEmpty())
        val r2 = StrokeEraser.cut(same, 90f, 90f, 90f, 90f, 5f)
        assertTrue(r2 == null || r2.all { it.points.isNotEmpty() })

        // pressure the device did not report stays unreported in the pieces, cut points included
        val nan = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 0f, 100)), InkTool.FINGER, thin)
        val pieces = assertNotNull(StrokeEraser.cut(nan, 50f, 0f, 50f, 0f, 5f))
        assertTrue(pieces.all { p -> p.points.all { it.pressure.isNaN() } })

        // an eraser larger than the stroke removes it
        assertEquals(emptyList(), StrokeEraser.cut(line(0f, 10f), 5f, 0f, 5f, 0f, 500f))

        // a zero-length capsule works as a circle: it does not reach a line 8 away
        assertNull(StrokeEraser.cut(line(0f, 100f, y = 8f), 50f, 0f, 50f, 0f, 5f))
        assertEquals(2, assertNotNull(StrokeEraser.cut(line(0f, 100f, y = 3f), 50f, 0f, 50f, 0f, 5f)).size)
    }

    @Test
    fun cutsPolylineCornersAndKeepsAllPiecesOrdered() {
        // a zig-zag whose two verticals are crossed by one wide step
        val s = InkStroke(
            listOf(InkPoint(0f, 0f, 0), InkPoint(40f, 0f, 40), InkPoint(40f, 40f, 80), InkPoint(80f, 40f, 120), InkPoint(80f, 0f, 160), InkPoint(120f, 0f, 200)),
            InkTool.STYLUS, thin,
        )
        val pieces = assertNotNull(StrokeEraser.cut(s, 30f, 20f, 90f, 20f, 4f))
        assertEquals(3, pieces.size)
        assertTrue(pieces.zipWithNext().all { (a, b) -> a.points.last().timeMs <= b.points.first().timeMs })
    }
}
