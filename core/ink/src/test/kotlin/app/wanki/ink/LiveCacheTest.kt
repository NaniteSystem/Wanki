package app.wanki.ink

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveCacheTest {
    private fun stroke(x: Float) = InkStroke(listOf(InkPoint(x, 0f, 0), InkPoint(x, 10f, 10)))

    private val a = stroke(0f)
    private val b = stroke(100f)
    private val c = stroke(200f)

    @Test
    fun conversionOfAnOldSnapshotDoesNotBringAnErasedStrokeBack() {
        val cache = LiveCache<InkStroke, String>().apply { reset(listOf(a, b, c)) }
        val snapshot = listOf(a, b, c) // a tile took its strokes
        val started = CountDownLatch(1)
        val erased = CountDownLatch(1)
        val results = HashMap<InkStroke, String>()
        val worker = thread {
            for (s in snapshot) {
                results[s] = cache.getOrPut(s) {
                    if (s === b) { started.countDown(); erased.await(5, TimeUnit.SECONDS) }
                    "converted ${s.points[0].x}"
                }
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        cache.remove(b) // erased on the main thread while b's conversion runs
        erased.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)

        assertEquals("converted 100.0", results[b], "the old snapshot still gets its picture")
        assertFalse(cache.has(b), "an erased stroke is not cached")
        assertTrue(cache.has(a))
        assertTrue(cache.has(c))
        assertEquals(2, cache.size)
    }

    @Test
    fun readAheadOfferAfterEraseIsRejected() {
        val cache = LiveCache<InkStroke, String>().apply { reset(listOf(a, b)) }
        assertTrue(cache.offer(a, "a"))
        cache.remove(b)
        assertFalse(cache.offer(b, "b"), "the read-ahead converted b from the page as it was read")
        assertFalse(cache.has(b))
        assertFalse(cache.offer(a, "again"), "a value already stored is kept")
        assertEquals("a", cache[a])
    }

    @Test
    fun eraseDropsAStoredValueAndUndoMayCacheItAgain() {
        val cache = LiveCache<InkStroke, String>().apply { reset(listOf(a, b)) }
        cache.getOrPut(b) { "b" }
        cache.remove(b)
        assertFalse(cache.has(b))
        assertEquals("b2", cache.getOrPut(b) { "b2" })
        assertFalse(cache.has(b), "still erased: converted but not kept")
        cache.add(b) // undo
        assertEquals("b3", cache.getOrPut(b) { "b3" })
        assertTrue(cache.has(b))
    }

    @Test
    fun resetDropsStrokesThatAreGone() {
        val cache = LiveCache<InkStroke, String>().apply { reset(listOf(a, b)) }
        cache.getOrPut(a) { "a" }
        cache.getOrPut(b) { "b" }
        cache.reset(listOf(b, c)) // a replace (or an erase drag cancelled / undone)
        assertFalse(cache.has(a))
        assertTrue(cache.has(b))
        assertFalse(cache.offer(a, "a"))
        assertTrue(cache.offer(c, "c"))
    }

    @Test
    fun aStrokeTwiceInTheListStaysLiveUntilItsLastCopyGoes() {
        val cache = LiveCache<InkStroke, String>().apply { reset(listOf(a, a)) }
        cache.getOrPut(a) { "a" }
        cache.remove(a)
        assertTrue(cache.has(a))
        cache.remove(a)
        assertFalse(cache.has(a))
        assertFalse(cache.isLive(a))
    }

    @Test
    fun nullValuesAreRemembered() {
        val cache = LiveCache<InkStroke, String?>().apply { reset(listOf(a)) }
        var made = 0
        assertNull(cache.getOrPut(a) { made++; null })
        assertNull(cache.getOrPut(a) { made++; "late" })
        assertEquals(1, made, "a stroke that cannot be converted is not tried again")
        assertTrue(cache.has(a))
    }
}
