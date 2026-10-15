package app.wanki.ink

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GesturesTest {
    private fun stroke(vararg xy: Pair<Float, Float>) =
        InkStroke(xy.mapIndexed { i, (x, y) -> InkPoint(x, y, i * 8L) })

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20) =
        InkStroke((0..n).map { k -> InkPoint(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, k * 8L) })

    /** Back and forth over [x0, x1] while slowly moving down — a typical scribble. */
    private fun zigzag(x0: Float, x1: Float, y0: Float, y1: Float, passes: Int): InkStroke {
        val pts = ArrayList<InkPoint>()
        var t = 0L
        for (p in 0 until passes) {
            val y = y0 + (y1 - y0) * p / passes
            for (k in 0..10) {
                val f = k / 10f
                val x = if (p % 2 == 0) x0 + (x1 - x0) * f else x1 - (x1 - x0) * f
                pts += InkPoint(x, y + 3 * sin(f * PI).toFloat(), t)
                t += 8
            }
        }
        return InkStroke(pts)
    }

    @Test
    fun `zigzag is a scratch-out, ordinary writing is not`() {
        assertTrue(Gestures.isScratchOut(zigzag(100f, 220f, 50f, 80f, 7)))
        assertFalse(Gestures.isScratchOut(line(0f, 0f, 200f, 0f)))
        // a written "m"/wave: some reversals but not a dense scribble
        assertFalse(Gestures.isScratchOut(stroke(0f to 40f, 10f to 0f, 20f to 40f, 30f to 0f, 40f to 40f, 50f to 0f, 60f to 40f, 70f to 0f, 80f to 40f, 90f to 0f, 100f to 40f, 110f to 0f, 120f to 40f)))
        // a 一 written slowly with jitter is not a scratch-out either
        val jitter = InkStroke((0..60).map { k -> InkPoint(k * 3f + if (k % 2 == 0) 0.5f else -0.5f, 10f, k * 8L) })
        assertFalse(Gestures.isScratchOut(jitter))
    }

    @Test
    fun `scratch-out picks the strokes it covers`() {
        val word = listOf(line(110f, 60f, 200f, 60f), line(120f, 50f, 120f, 75f)) // under the scratch
        val other = line(400f, 60f, 500f, 60f)
        val scratch = zigzag(100f, 220f, 45f, 80f, 8)
        val all = word + other + scratch
        assertEquals(listOf(0, 1), Gestures.scratchedStrokes(scratch, all))
    }
}
