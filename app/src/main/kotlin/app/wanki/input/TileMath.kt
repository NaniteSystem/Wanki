package app.wanki.input

import app.wanki.ink.InkRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Tile grid of the ink cache: 512 × 512 px tiles at power-of-two zoom levels. At level L one tile
 * covers [tileWorld] = 512 / 2^L world units, so a tile pixel is 2^L per world unit.
 */
object TileMath {
    const val TILE_PX = 512
    const val MIN_LEVEL = -4
    const val MAX_LEVEL = 3

    data class TileKey(val level: Int, val col: Int, val row: Int)

    /** The zoom level whose tiles are closest to 1:1 at [scale] (screen px per world unit). */
    fun level(scale: Float): Int {
        if (!scale.isFinite() || scale <= 0f) return 0
        return log2(scale).roundToInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
    }

    /** World size of one tile side at [level]. */
    fun tileWorld(level: Int): Float = TILE_PX / pixelsPerWorld(level)

    /** Tile pixels per world unit at [level]: 2^level. */
    fun pixelsPerWorld(level: Int): Float = if (level >= 0) (1 shl level).toFloat() else 1f / (1 shl -level)

    /** World rectangle of tile [k]. */
    fun rect(k: TileKey): InkRect {
        val t = tileWorld(k.level)
        return InkRect(k.col * t, k.row * t, (k.col + 1) * t, (k.row + 1) * t)
    }

    /** Tiles of [level] that touch [rect]; a rectangle ending exactly on a tile edge does not take the next tile. */
    fun covering(rect: InkRect, level: Int): List<TileKey> {
        val t = tileWorld(level)
        val c0 = floor(rect.left / t).toInt()
        val r0 = floor(rect.top / t).toInt()
        val c1 = maxOf(c0, ceil(rect.right / t).toInt() - 1)
        val r1 = maxOf(r0, ceil(rect.bottom / t).toInt() - 1)
        val out = ArrayList<TileKey>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (r in r0..r1) for (c in c0..c1) out += TileKey(level, c, r)
        return out
    }

    /** Tiles for a view of [rect]: [covering] plus one tile of margin on every side, the view's own tiles first. */
    fun visible(rect: InkRect, level: Int): List<TileKey> {
        val inner = covering(rect, level)
        val t = tileWorld(level)
        val outer = covering(InkRect(rect.left - t, rect.top - t, rect.right + t, rect.bottom + t), level)
        val seen = inner.toHashSet()
        return inner + outer.filter { it !in seen }
    }
}

/** Least-recently-used byte budget for tiles. [put] returns the keys to drop to stay within [maxBytes]. */
class TileBudget(val maxBytes: Long) {
    private val entries = LinkedHashMap<TileMath.TileKey, Long>(64, 0.75f, true)

    var bytes = 0L
        private set

    fun put(key: TileMath.TileKey, size: Long): List<TileMath.TileKey> {
        entries.put(key, size)?.let { bytes -= it }
        bytes += size
        val evicted = ArrayList<TileMath.TileKey>()
        val it = entries.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= e.value
            evicted += e.key
            it.remove()
        }
        return evicted
    }

    /** Marks [key] as just used. */
    fun touch(key: TileMath.TileKey) {
        entries[key]
    }

    fun remove(key: TileMath.TileKey) {
        entries.remove(key)?.let { bytes -= it }
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }
}
