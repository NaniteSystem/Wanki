package app.wanki.input

import app.wanki.ink.InkRect
import app.wanki.input.TileMath.TileKey
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileMathTest {
    @Test
    fun levels() {
        assertEquals(0, TileMath.level(1f))
        assertEquals(2, TileMath.level(2.9f))
        assertEquals(-4, TileMath.level(0.05f))
        assertEquals(3, TileMath.level(20f))
        assertEquals(512f, TileMath.tileWorld(0))
        assertEquals(64f, TileMath.tileWorld(3))
        assertEquals(8192f, TileMath.tileWorld(-4))
    }

    private fun rectOf(k: TileKey): InkRect {
        val t = TileMath.tileWorld(k.level)
        return InkRect(k.col * t, k.row * t, (k.col + 1) * t, (k.row + 1) * t)
    }

    /** True when the tiles cover every point of [view] (checked on a fine sample grid). */
    private fun covers(tiles: List<TileKey>, view: InkRect): Boolean {
        val set = tiles.toSet()
        val level = tiles.first().level
        val t = TileMath.tileWorld(level)
        val steps = 50
        for (i in 0..steps) for (j in 0..steps) {
            val x = view.left + view.width * i / steps
            val y = view.top + view.height * j / steps
            if (TileKey(level, floor(x / t).toInt(), floor(y / t).toInt()) !in set) return false
        }
        return true
    }

    @Test
    fun visibleCoversView() {
        val scale = 1.08f
        val level = TileMath.level(scale)
        // a 1080 × 2400 px view at a few offsets, in world units
        for (ox in listOf(0f, -137f, 250f)) for (oy in listOf(0f, -611f, 33f)) {
            val view = InkRect(-ox / scale, -oy / scale, (1080f - ox) / scale, (2400f - oy) / scale)
            val covering = TileMath.covering(view, level)
            assertTrue(covering.size <= 3 * 7, "covering ${covering.size}")
            assertTrue(covers(covering, view))
            val visible = TileMath.visible(view, level)
            assertTrue(visible.containsAll(covering))
            // one tile of margin on every side
            assertTrue(visible.size <= (3 + 2) * (7 + 2), "visible ${visible.size}")
            val margin = TileMath.tileWorld(level)
            val wider = InkRect(view.left - margin + 1f, view.top - margin + 1f, view.right + margin - 1f, view.bottom + margin - 1f)
            assertTrue(covers(visible, wider))
            assertTrue(visible.all { rectOf(it).intersects(InkRect(view.left - margin, view.top - margin, view.right + margin, view.bottom + margin)) })
        }
    }

    @Test
    fun coveringNegative() {
        val tiles = TileMath.covering(InkRect(-600f, -10f, -1f, 10f), 0).toSet()
        assertEquals(setOf(TileKey(0, -2, -1), TileKey(0, -1, -1), TileKey(0, -2, 0), TileKey(0, -1, 0)), tiles)
        // a rectangle ending exactly on a tile edge does not take the next tile
        assertEquals(listOf(TileKey(1, -1, 0)), TileMath.covering(InkRect(-256f, 0f, 0f, 256f), 1))
        assertEquals(listOf(TileKey(0, 0, 0)), TileMath.covering(InkRect(5f, 5f, 5f, 5f), 0))
    }

    @Test
    fun budgetCapsTiles() {
        val tile = 512L * 512 * 4
        val budget = TileBudget(4 * tile)
        val keys = (0 until 5).map { TileKey(0, it, 0) }
        for (k in keys.take(4)) assertEquals(emptyList(), budget.put(k, tile))
        budget.touch(keys[0]) // keys[1] is now the least recently used
        assertEquals(listOf(keys[1]), budget.put(keys[4], tile))
        assertEquals(4 * tile, budget.bytes)
        budget.remove(keys[0])
        assertEquals(3 * tile, budget.bytes)
        assertEquals(emptyList(), budget.put(keys[1], tile))
    }
}
