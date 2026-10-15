package app.wanki.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InkCodecTest {
    private fun sample(): InkDrawing {
        val strokes = (0 until 12).map { s ->
            InkStroke(
                (0 until 80).map { i ->
                    val a = i / 10.0
                    InkPoint(
                        x = (300 + s * 40 + 120 * cos(a)).toFloat(),
                        y = (800 + 90 * sin(a) - s * 3).toFloat(),
                        timeMs = s * 900L + i * 8L,
                        pressure = (0.2 + 0.6 * i / 80).toFloat(),
                        tiltRadians = 0.6f,
                        orientationRadians = 4.0f,
                    )
                },
                InkTool.STYLUS,
            )
        } + InkStroke(listOf(InkPoint(-12.5f, 3f, 20_000)), InkTool.FINGER)
        return InkDrawing(strokes)
    }

    @Test
    fun `round trip within quantization error`() {
        val d = sample()
        val back = InkCodec.decode(InkCodec.encode(d))
        assertEquals(d.strokes.size, back.strokes.size)
        for ((a, b) in d.strokes.zip(back.strokes)) {
            assertEquals(a.tool, b.tool)
            assertEquals(a.points.size, b.points.size)
            for ((p, q) in a.points.zip(b.points)) {
                assertTrue(abs(p.x - q.x) <= 1f / 32 + 1e-4 && abs(p.y - q.y) <= 1f / 32 + 1e-4, "$p vs $q")
                assertEquals(p.timeMs, q.timeMs)
                if (p.pressure.isNaN()) assertTrue(q.pressure.isNaN()) else assertEquals(p.pressure, q.pressure, 1e-3f)
                if (!p.tiltRadians.isNaN()) assertEquals(p.tiltRadians, q.tiltRadians, 2e-3f)
                if (!p.orientationRadians.isNaN()) assertEquals(p.orientationRadians, q.orientationRadians, 7e-3f)
            }
        }
    }

    @Test
    fun `is compact`() {
        val d = sample()
        val points = d.strokes.sumOf { it.points.size }
        val bytes = InkCodec.encode(d).size
        assertTrue(bytes < points * 9, "$bytes bytes for $points points")
    }

    @Test
    fun `empty drawing and bad input`() {
        assertEquals(InkDrawing.EMPTY, InkCodec.decode(InkCodec.encode(InkDrawing.EMPTY)))
        assertFailsWith<IllegalArgumentException> { InkCodec.decode(byteArrayOf(1, 2, 3)) }
        val truncated = InkCodec.encode(sample()).copyOf(40)
        assertFailsWith<IllegalArgumentException> { InkCodec.decode(truncated) }
    }

    @Test
    fun `duration spans all strokes`() {
        assertEquals(20_000L, sample().durationMs)
    }
}
