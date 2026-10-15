package app.wanki.scoring

import app.wanki.scoring.geom.Point
import app.wanki.scoring.geom.SvgPath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanjiVgTest {
    @Test
    fun `parses strokes types and components of 右`() {
        val ref = Fixtures.kanji("右")
        assertEquals("右", ref.char)
        assertEquals(5, ref.strokes.size)
        assertEquals(listOf("㇒", "㇐", "㇑", "㇕b", "㇐b"), ref.strokes.map { it.type })
        val parts = ref.topLevelParts()
        assertEquals(listOf("丆", "口"), parts.map { it.element })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), parts.map { it.strokeIndices })
    }

    @Test
    fun `stroke endpoints match the path data`() {
        val first = Fixtures.kanji("右").strokes[0].points
        assertEquals(Point(53.5, 21.5), first.first())
        // M53.5,21.5 c0.62,1.12,0.69,2.23,0.25,4 → (53.75, 25.5), then C…25.25,74.25
        assertTrue(abs(first.last().x - 25.25) < 1e-9 && abs(first.last().y - 74.25) < 1e-9)
    }

    @Test
    fun `smooth cubic reflects the previous control point`() {
        val line = SvgPath.flatten("M0,0 C0,10 10,10 10,0 S20,-10 20,0", curveSegments = 2)
        // second curve's implicit c1 = reflection of (10,10) around (10,0) = (10,-10); midpoint at t=.5:
        // .125*10 + .375*10 + .375*20 + .125*20 = 15 ; y = .375*-10 + .375*-10 = -7.5
        assertEquals(Point(15.0, -7.5), line[3])
    }

    @Test
    fun `all fixtures parse`() {
        val dir = javaClass.getResource("/kanjivg")!!.toURI().let { java.io.File(it) }
        val files = dir.listFiles { f -> f.extension == "svg" }!!
        assertTrue(files.size >= 20)
        for (f in files) {
            val ref = app.wanki.scoring.kanjivg.KanjiVg.parse(f.readText())
            assertTrue(ref.strokes.isNotEmpty(), f.name)
            assertTrue(ref.strokes.all { s -> s.points.all { it.x in -5.0..114.0 && it.y in -5.0..114.0 } }, f.name)
        }
    }
}
