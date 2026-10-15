package app.wanki.ink

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LineSegmenterTest {
    private var t = 0L

    /** A fake "character": a few strokes inside a box. */
    private fun glyph(x: Float, y: Float, size: Float, r: Random): List<InkStroke> = (0 until 3).map {
        val x0 = x + r.nextFloat() * size * 0.3f
        val y0 = y + r.nextFloat() * size * 0.8f
        val horizontal = r.nextBoolean()
        InkStroke((0..10).map { k ->
            val f = k / 10f
            InkPoint(
                if (horizontal) x0 + f * size * 0.7f else x0 + size * 0.1f,
                if (horizontal) y0 else y + f * size,
                t++,
            )
        })
    }

    private fun dot(x: Float, y: Float) = InkStroke(listOf(InkPoint(x, y, t++), InkPoint(x + 2, y + 2, t++)))

    @Test
    fun `splits a page into lines in reading order`() {
        val r = Random(1)
        val baselines = listOf(100f, 220f, 345f, 460f)
        val perLine = baselines.map { y ->
            (0 until 8).flatMap { c -> glyph(40f + c * 70f, y + r.nextFloat() * 12 - 6, 60f, r) } +
                dot(40f + 8 * 70f, y + 55f) // a 、 at the end of the line
        }
        // writing order must not matter: lines written bottom-up and strokes shuffled
        val strokes = perLine.reversed().flatten().shuffled(r)
        val lines = LineSegmenter.segment(strokes).map { l -> l.map { strokes[it] }.toSet() }
        assertEquals(perLine.map { it.toSet() }, lines)
    }

    @Test
    fun `single line and empty page`() {
        val r = Random(2)
        val strokes = (0 until 5).flatMap { glyph(it * 70f, 0f, 60f, r) }
        assertEquals(1, LineSegmenter.segment(strokes).size)
        assertEquals(emptyList(), LineSegmenter.segment(emptyList()))
    }
}
