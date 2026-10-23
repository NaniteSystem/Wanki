package app.wanki.ink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialGridTest {
    private fun stroke() = InkStroke(listOf(InkPoint(0f, 0f, 0)))

    @Test
    fun queryFindsOverlapping() {
        val g = SpatialGrid<InkStroke>(256f)
        val s = stroke()
        g.insert(s, InkRect(200f, 200f, 300f, 300f)) // spans 4 cells
        val hit = g.query(InkRect(0f, 0f, 600f, 600f))
        assertEquals(1, hit.size)
        assertTrue(g.query(InkRect(290f, 290f, 295f, 295f)).contains(s))
        assertTrue(g.query(InkRect(1000f, 1000f, 1100f, 1100f)).isEmpty())
    }

    @Test
    fun removeClearsAllCells() {
        val g = SpatialGrid<InkStroke>(256f)
        val s = stroke()
        g.insert(s, InkRect(200f, 200f, 300f, 300f))
        g.remove(s)
        assertEquals(0, g.size)
        assertTrue(g.query(InkRect(-1000f, -1000f, 1000f, 1000f)).isEmpty())
        assertTrue(g.query(InkRect(290f, 290f, 295f, 295f)).isEmpty())
    }

    @Test
    fun identityNotEquality() {
        val g = SpatialGrid<InkStroke>()
        val a = stroke()
        val b = stroke()
        assertEquals(a, b)
        g.insert(a, InkRect(0f, 0f, 10f, 10f))
        g.insert(b, InkRect(0f, 0f, 10f, 10f))
        assertEquals(2, g.size)
        assertEquals(2, g.query(InkRect(0f, 0f, 5f, 5f)).size)
        g.remove(a)
        assertEquals(1, g.size)
        assertEquals(1, g.query(InkRect(0f, 0f, 5f, 5f)).size)
    }

    @Test
    fun negativeCoordinates() {
        val g = SpatialGrid<InkStroke>()
        val s = stroke()
        g.insert(s, InkRect(-310f, -310f, -290f, -290f))
        assertTrue(g.query(InkRect(-300f, -300f, -299f, -299f)).contains(s))
        assertTrue(g.query(InkRect(0f, 0f, 10f, 10f)).isEmpty())
    }

    @Test
    fun hugeRectIsBounded() {
        val g = SpatialGrid<InkStroke>()
        val big = stroke()
        g.insert(big, InkRect(-50_000f, -50_000f, 50_000f, 50_000f))
        assertEquals(1, g.size)
        assertTrue(g.query(InkRect(7f, 7f, 8f, 8f)).contains(big))
        assertTrue(g.query(InkRect(90_000f, 90_000f, 90_001f, 90_001f)).contains(big))
        g.remove(big)
        assertEquals(0, g.size)
        assertTrue(g.query(InkRect(7f, 7f, 8f, 8f)).isEmpty())
    }
}
