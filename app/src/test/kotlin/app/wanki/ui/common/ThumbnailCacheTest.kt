package app.wanki.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThumbnailCacheTest {
    @Test
    fun evictsLeastRecentlyUsed() {
        val cache = ThumbnailCache<String>(maxEntries = 2)
        cache.put("a", 1L, "A")
        cache.put("b", 1L, "B")
        assertEquals("A", cache.get("a", 1L)) // a is now the most recent
        cache.put("c", 1L, "C")
        assertNull(cache.get("b", 1L))
        assertEquals("A", cache.get("a", 1L))
        assertEquals("C", cache.get("c", 1L))
    }

    @Test
    fun newUpdatedAtMisses() {
        val cache = ThumbnailCache<String>()
        cache.put("a", 1L, "old")
        assertNull(cache.get("a", 2L))
        assertEquals("old", cache.get("a", 1L))
    }
}
