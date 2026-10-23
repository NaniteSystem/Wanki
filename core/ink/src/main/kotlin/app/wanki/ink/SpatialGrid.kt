package app.wanki.ink

import java.util.IdentityHashMap
import kotlin.math.floor

/**
 * Uniform grid index over rectangles. Items are tracked by identity, so equal items are separate
 * entries. A rect covering more than [MAX_CELLS] cells goes to an oversize list returned by every query.
 */
class SpatialGrid<T : Any>(val cellSize: Float = 256f) {
    private val cells = HashMap<Long, MutableList<T>>()
    private val entries = IdentityHashMap<T, Entry>()
    private val oversize = ArrayList<T>()

    private class Entry(val keys: LongArray?)

    val size: Int get() = entries.size

    fun insert(item: T, rect: InkRect) {
        remove(item)
        val x0 = cell(rect.left); val x1 = cell(rect.right)
        val y0 = cell(rect.top); val y1 = cell(rect.bottom)
        val count = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (count > MAX_CELLS || count <= 0) {
            oversize += item
            entries[item] = Entry(null)
            return
        }
        val keys = LongArray(count.toInt())
        var i = 0
        for (cx in x0..x1) for (cy in y0..y1) {
            val k = key(cx, cy)
            cells.getOrPut(k) { ArrayList(4) } += item
            keys[i++] = k
        }
        entries[item] = Entry(keys)
    }

    fun remove(item: T) {
        val e = entries.remove(item) ?: return
        if (e.keys == null) {
            oversize.removeAll { it === item }
            return
        }
        for (k in e.keys) {
            val list = cells[k] ?: continue
            list.removeAll { it === item }
            if (list.isEmpty()) cells.remove(k)
        }
    }

    /** Items whose cells overlap [rect] (a superset of the exact intersection), each once. */
    fun query(rect: InkRect): Set<T> {
        val found = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        found.addAll(oversize)
        val x0 = cell(rect.left); val x1 = cell(rect.right)
        val y0 = cell(rect.top); val y1 = cell(rect.bottom)
        val count = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (count > cells.size) {
            // Query area is larger than the populated grid: scan the populated cells instead.
            for ((k, list) in cells) {
                val cx = (k shr 32).toInt(); val cy = k.toInt()
                if (cx in x0..x1 && cy in y0..y1) found.addAll(list)
            }
        } else {
            for (cx in x0..x1) for (cy in y0..y1) cells[key(cx, cy)]?.let { found.addAll(it) }
        }
        return found
    }

    fun clear() {
        cells.clear(); entries.clear(); oversize.clear()
    }

    private fun cell(v: Float): Long = floor(v / cellSize).toLong().coerceIn(-CELL_LIMIT, CELL_LIMIT)
    private fun key(cx: Long, cy: Long): Long = (cx shl 32) or (cy and 0xFFFFFFFFL)

    private companion object {
        const val MAX_CELLS = 4096L
        const val CELL_LIMIT = 1_000_000_000L
    }
}
