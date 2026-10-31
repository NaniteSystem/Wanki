package app.wanki.input

import app.wanki.data.PageObjectKind
import app.wanki.ink.InkAffine
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageObjectsTest {
    private fun obj(rotation: Float = 0f) = PageObject(
        id = "o1", kind = PageObjectKind.TEXT, x = 100f, y = 200f, w = 600f, h = 80f, rotation = rotation, z = 3,
        text = "Привет", color = 0xFF112233.toInt(), textSize = 32f, media = null,
    )

    @Test
    fun rotatedBoundsContainCorners() {
        for (r in listOf(0f, 30f, 90f, 135f, 270f)) {
            val o = obj(r)
            val b = o.bounds()
            for ((x, y) in o.corners()) {
                assertTrue(x >= b.left - 1e-3f && x <= b.right + 1e-3f && y >= b.top - 1e-3f && y <= b.bottom + 1e-3f, "r=$r ($x, $y) in $b")
            }
            // the box is tight: some corner reaches each side
            assertEquals(b.left, o.corners().minOf { it.first }, 1e-3f)
            assertEquals(b.bottom, o.corners().maxOf { it.second }, 1e-3f)
            // centred where the object is
            assertEquals(o.centerX, (b.left + b.right) / 2, 1e-2f)
            assertEquals(o.centerY, (b.top + b.bottom) / 2, 1e-2f)
        }
        // a quarter turn swaps width and height
        val q = obj(90f).bounds()
        assertEquals(80f, q.width, 1e-2f)
        assertEquals(600f, q.height, 1e-2f)
    }

    @Test
    fun transformScalesTextSize() {
        val o = obj()
        val m = InkAffine.scaleAbout(o.centerX, o.centerY, 2f).then(InkAffine.rotateAbout(o.centerX, o.centerY, (PI / 2).toFloat()))
        val t = o.transformed(m)
        assertEquals(64f, t.textSize, 1e-3f)
        assertEquals(1200f, t.w, 1e-2f)
        assertEquals(160f, t.h, 1e-2f)
        assertEquals(90f, t.rotation, 1e-3f)
        assertEquals(o.centerX, t.centerX, 1e-2f)
        assertEquals(o.centerY, t.centerY, 1e-2f)
        // a move keeps the size
        val moved = o.transformed(InkAffine.translate(10f, -5f))
        assertEquals(110f, moved.x, 1e-3f)
        assertEquals(195f, moved.y, 1e-3f)
        assertEquals(32f, moved.textSize)
        assertEquals(o.id, t.id)
    }

    @Test
    fun entityRoundTrip() {
        val o = obj(15f).copy(kind = PageObjectKind.IMAGE, media = "page-x.jpg")
        val e = o.toEntity("page-1", now = 42L)
        assertEquals("page-1", e.pageId)
        assertEquals("IMAGE", e.kind)
        assertEquals(42L, e.updatedAt)
        assertEquals(o, e.toModel())
        assertEquals(null, e.copy(kind = "MASK").toModelOrNull())
    }

    @Test
    fun pictureCacheEvictsLeastRecentlyUsedByBytes() {
        val c = SizedLru<String, Long>(100) { it }
        c.put("a", 40)
        c.put("b", 40)
        c.get("a") // a is now the most recent
        c.put("c", 40) // 120 > 100: b (least recently used) goes
        assertEquals(setOf("a", "c"), c.keys)
        assertEquals(80L, c.total)
        // replacing a value counts only the new size
        c.put("a", 10)
        assertEquals(50L, c.total)
        // one value over the budget stays, alone
        c.put("big", 500)
        assertEquals(setOf("big"), c.keys)
        assertEquals(500L, c.total)
    }

    @Test
    fun decodeSideFollowsTheShownSize() {
        assertEquals(256, PageObjectPainter.decodeSide(10f))
        assertEquals(512, PageObjectPainter.decodeSide(300f))
        assertEquals(1024, PageObjectPainter.decodeSide(1024f))
        assertEquals(2048, PageObjectPainter.decodeSide(9000f))
    }
}
