package app.wanki.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
                InkBrush(BrushKind.entries[s % 3], (0xFF000000.toInt() or (s * 0x10203)), 3.5f + s),
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
            assertEquals(a.brush, b.brush)
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

    private fun assertClose(a: InkDrawing, b: InkDrawing) {
        assertEquals(a.strokes.size, b.strokes.size)
        for ((x, y) in a.strokes.zip(b.strokes)) {
            assertEquals(x.tool, y.tool)
            assertEquals(x.brush, y.brush)
            assertEquals(x.points.size, y.points.size)
            for ((p, q) in x.points.zip(y.points)) {
                assertTrue(abs(p.x - q.x) <= 1f / 16 && abs(p.y - q.y) <= 1f / 16, "$p vs $q")
                assertEquals(p.timeMs, q.timeMs)
                assertEquals(p.pressure.isNaN(), q.pressure.isNaN())
                if (!p.pressure.isNaN()) assertEquals(p.pressure, q.pressure, 1f / 1023)
                if (!p.tiltRadians.isNaN()) assertEquals(p.tiltRadians, q.tiltRadians, 2e-3f)
                if (!p.orientationRadians.isNaN()) assertEquals(p.orientationRadians, q.orientationRadians, 7e-3f)
            }
        }
    }

    @Test
    fun readsV2Fixture() {
        val bytes = javaClass.getResourceAsStream("/ink-v2.bin")!!.readBytes()
        assertEquals(2, bytes[4].toInt())
        val d = InkCodec.decode(bytes)
        assertEquals(3, d.strokes.size)
        val (stylus, finger, hl) = d.strokes
        assertEquals(InkTool.STYLUS, stylus.tool)
        assertEquals(InkBrush(BrushKind.PEN, 0xFF112233.toInt(), 3.5f), stylus.brush)
        assertEquals(20, stylus.points.size)
        stylus.points.forEachIndexed { i, p ->
            assertEquals(10f + i * 3.3f, p.x, 1f / 16)
            assertEquals(50f + i * i * 0.37f, p.y, 1f / 16)
            assertEquals(100L + i * 8, p.timeMs)
            assertEquals(0.1f + i * 0.04f, p.pressure, 1f / 1023)
            assertEquals(0.5f, p.tiltRadians, 2e-3f)
            assertEquals(3.0f, p.orientationRadians, 7e-3f)
        }
        assertEquals(InkTool.FINGER, finger.tool)
        assertEquals(InkBrush.DEFAULT, finger.brush)
        assertEquals(10, finger.points.size)
        assertTrue(finger.points.all { it.pressure.isNaN() })
        assertEquals(-20f, finger.points[0].x, 1f / 16)
        assertEquals(BrushKind.HIGHLIGHTER, hl.brush.kind)
        assertEquals(0x80FFEE00.toInt(), hl.brush.color)
        assertEquals(18f, hl.brush.size)
        assertEquals(6, hl.points.size)
        assertEquals(0.5f, hl.points[5].pressure, 1f / 1023)
        assertEquals(2100L, hl.points[5].timeMs)
        assertTrue(hl.points[5].tiltRadians.isNaN())
    }

    @Test
    fun v3RoundTrip() {
        val d = sample()
        val bytes = InkCodec.encode(d)
        assertEquals(3, bytes[4].toInt())
        assertClose(d, InkCodec.decode(bytes))
        assertEquals(InkDrawing.EMPTY, InkCodec.decode(InkCodec.encode(InkDrawing.EMPTY)))
        val one = InkDrawing(listOf(InkStroke(listOf(InkPoint(1.5f, -2.25f, 7)), InkTool.MOUSE)))
        assertClose(one, InkCodec.decode(InkCodec.encode(one)))
        val empty = InkDrawing(listOf(InkStroke(emptyList())))
        assertClose(empty, InkCodec.decode(InkCodec.encode(empty)))
    }

    @Test
    fun assembleEqualsEncode() {
        val d = sample()
        assertContentEquals(InkCodec.encode(d), InkCodec.assemble(d.strokes.map(InkCodec::encodeStroke)))
    }

    @Test
    fun chunkIsIndependentOfNeighbours() {
        val d = sample()
        val b = d.strokes[5]
        val alone = InkCodec.encodeStroke(b)
        val inContext = InkCodec.decode(InkCodec.encode(InkDrawing(listOf(d.strokes[4], b)))).strokes[1]
        assertContentEquals(alone, InkCodec.encodeStroke(inContext))
        assertContentEquals(alone, InkCodec.encodeStroke(b))
    }

    @Test
    fun strokeKeyDiffersOnOnePointMove() {
        val s = sample().strokes[0]
        val moved = s.copy(points = s.points.mapIndexed { i, p -> if (i == 40) p.copy(x = p.x + 0.0625f) else p })
        assertNotEquals(InkCodec.strokeKey(InkCodec.encodeStroke(s)), InkCodec.strokeKey(InkCodec.encodeStroke(moved)))
        assertEquals(InkCodec.strokeKey(InkCodec.encodeStroke(s)), InkCodec.strokeKey(InkCodec.encodeStroke(s)))
    }

    @Test
    fun truncatedV3Fails() {
        val bytes = InkCodec.encode(sample())
        assertFailsWith<IllegalArgumentException> { InkCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }
}
