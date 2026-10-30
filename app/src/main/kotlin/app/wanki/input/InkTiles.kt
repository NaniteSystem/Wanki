package app.wanki.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.TileMath.TileKey
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Finished ink of a pan/zoom canvas as cached 512 × 512 px bitmaps ([TileMath]), so a frame draws a
 * few dozen bitmaps instead of every stroke.
 *
 * - Tiles render one at a time on a background thread through [InkRaster]; the stroke list of a tile is
 *   taken on the main thread ([InkLayer.strokesIn] is main-thread only) and handed over.
 * - A missing or dirty tile is drawn stroke by stroke (clipped to the tile) until its bitmap is ready.
 *   A stroke added on top only needs itself drawn over the tile's older bitmap until then.
 * - During a pinch the current level's tiles are drawn scaled; the new level renders after it ends and
 *   the old level's tiles stand in until then.
 * - [exclude]d strokes (a live selection) stay out of the bitmaps and are drawn directly on top.
 *
 * Everything here runs on the main thread except the bitmap rendering itself; [scope] must be a
 * main-thread scope (the composition's).
 */
class InkTiles(val layer: InkLayer, private val scope: CoroutineScope) {
    private class Tile(val key: TileKey) {
        /** Bumped by every change in the tile's area. */
        var gen = 0
        /** [gen] after the last change that was not a stroke added on top. Read by the render thread. */
        @Volatile var fullGen = 0
        /** The rendered picture (null for a tile without ink) and the generation it shows; -1 = none yet. */
        var bitmap: Bitmap? = null
        var imageGen = -1
        /** Strokes added on top after [imageGen], with the generation that added each. */
        val onTop = ArrayList<Pair<Int, InkStroke>>()
        var rendering = false

        /** The picture shows the tile as it is now. */
        val ready: Boolean get() = imageGen == gen
        /** The picture plus [onTop] shows the tile as it is now. */
        val usable: Boolean get() = imageGen >= fullGen
    }

    private val tiles = HashMap<TileKey, Tile>()
    private val budget = TileBudget(budgetBytes())
    private val directRenderer = CanvasStrokeRenderer.create()
    /** Used only on the render thread. */
    private val tileRenderer = CanvasStrokeRenderer.create()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private var level: Int? = null
    /** The level before the last switch: its tiles stand in for the new level's missing ones. */
    private var previousLevel: Int? = null
    private var excluded: Set<InkStroke> = emptySet()

    /** Bumped when a tile bitmap is ready, so the canvas draws again. */
    private var readyRev by mutableIntStateOf(0)

    /** Every tile under the view is drawn from a fresh bitmap of the current level (tests, speed measurements). */
    var allVisibleReady = false
        private set

    /** Strokes the last frame drew directly: in missing or dirty tiles, added on top, and the [exclude]d ones. */
    var pendingDirect: Set<InkStroke> = emptySet()
        private set

    /** The viewport of the last frame (tests). */
    var viewport: Viewport? = null
        private set

    private val listener = object : InkLayer.ChangeListener {
        override fun changed(area: InkRect) = invalidate(area)
        override fun appended(stroke: InkStroke, area: InkRect) {
            for (t in tiles.values) if (TileMath.rect(t.key).intersects(area)) {
                t.gen++
                t.onTop += t.gen to stroke
            }
        }
    }

    init {
        layer.addChangeListener(listener)
        live[this] = Unit
    }

    /** Draws the ink in world coordinates: [scope] is already transformed by [viewport]. */
    fun draw(scope: DrawScope, viewport: Viewport) {
        this.viewport = viewport
        // state read by the frame: a ready tile, an edit, a pinch ending
        @Suppress("UNUSED_VARIABLE") val rev = readyRev + layer.strokesRev
        val view = viewport.visibleWorld()
        if (view.width <= 0f || view.height <= 0f) return
        val target = TileMath.level(viewport.scale)
        var cur = level ?: target
        if (cur != target && (!viewport.pinching || TileMath.covering(view, cur).size > MAX_SCALED_TILES)) {
            previousLevel = cur
            cur = target
        }
        level = cur

        val canvas = scope.drawContext.canvas.nativeCanvas
        val matrix = viewport.worldToScreen()
        val direct = Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>())
        val inner = TileMath.covering(view, cur)
        var allReady = true
        var directLeft = MAX_DIRECT_STROKES
        for (key in inner) {
            val tile = tiles[key]
            if (tile?.ready != true) allReady = false
            if (tile != null && tile.usable) {
                budget.touch(key)
                drawTile(canvas, tile, matrix, direct)
                continue
            }
            if (drawFromPrevious(canvas, key, matrix, direct)) continue
            val rect = TileMath.rect(key)
            val strokes = strokesFor(rect)
            // a tile without a picture of ink yet (a full page just opened) may wait for its bitmap rather than
            // stall the frame: until then it only lacks strokes. A dirty picture would be wrong: always draw those.
            val noInkYet = tile?.bitmap == null
            if (noInkYet && strokes.size > directLeft) continue
            directLeft -= strokes.size
            canvas.save()
            canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
            for (s in strokes) drawDirect(canvas, s, matrix, direct)
            canvas.restore()
        }
        allVisibleReady = allReady
        for (s in layer.inLayerOrder(excluded)) drawDirect(canvas, s, matrix, direct)
        pendingDirect = direct
        scheduleSoon(view, inner)
    }

    private fun drawDirect(canvas: Canvas, s: InkStroke, matrix: Matrix, drawn: MutableSet<InkStroke>) {
        drawn += s
        layer.renderable(s)?.let { directRenderer.draw(canvas, it, matrix) }
    }

    /** A usable tile: its picture, then the strokes added on top since, clipped to the tile. */
    private fun drawTile(canvas: Canvas, tile: Tile, matrix: Matrix, drawn: MutableSet<InkStroke>) {
        val r = TileMath.rect(tile.key)
        tile.bitmap?.let {
            dst.set(r.left, r.top, r.right, r.bottom)
            canvas.drawBitmap(it, null, dst, bitmapPaint)
        }
        if (tile.onTop.isEmpty()) return
        canvas.save()
        canvas.clipRect(r.left, r.top, r.right, r.bottom)
        for ((_, s) in tile.onTop) if (s !in excluded) drawDirect(canvas, s, matrix, drawn)
        canvas.restore()
    }

    /** Usable tiles of the previous level that cover all of [key]'s area, drawn clipped to it; false if any is missing. */
    private fun drawFromPrevious(canvas: Canvas, key: TileKey, matrix: Matrix, drawn: MutableSet<InkStroke>): Boolean {
        val prev = previousLevel ?: return false
        if (prev == key.level) return false
        val rect = TileMath.rect(key)
        val keys = TileMath.covering(rect, prev)
        if (keys.size > MAX_SCALED_TILES) return false
        if (!keys.all { tiles[it]?.usable == true }) return false
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        for (k in keys) drawTile(canvas, tiles.getValue(k), matrix, drawn)
        canvas.restore()
        return true
    }

    /** Strokes that may paint inside [rect], in layer order, without the excluded ones. Main thread. */
    private fun strokesFor(rect: InkRect): List<InkStroke> {
        // the grid answers a superset: keep the strokes that paint into the tile
        return layer.strokesIn(rect).filter { s -> s !in excluded && layer.paintedArea(s)?.intersects(rect) == true }
    }

    private var schedulePosted = false
    private var disposed = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The view of the latest frame, for the posted [schedule]. */
    private var lastView: InkRect? = null
    private var lastInner: List<TileKey> = emptyList()

    /** [schedule] right after this frame: gathering the tiles' strokes need not delay the frame. */
    private fun scheduleSoon(view: InkRect, inner: List<TileKey>) {
        lastView = view
        lastInner = inner
        if (schedulePosted) return
        schedulePosted = true
        // a plain message: the composition's dispatcher may run a launch inside this very frame
        mainHandler.post {
            schedulePosted = false
            if (disposed) return@post
            // frames drawn since this was posted only updated the view: use the latest one
            val v = lastView ?: return@post
            val l = level ?: return@post
            schedule(v, l, lastInner)
        }
    }

    /** Starts renders for the view's tiles, then (when the budget holds them all) for the margin around it. */
    private fun schedule(view: InkRect, level: Int, inner: List<TileKey>) {
        val all = TileMath.visible(view, level)
        val wanted = if (all.size * TILE_BYTES <= budget.maxBytes) all else inner
        for (key in wanted) {
            val tile = tiles.getOrPut(key) { Tile(key) }
            if (!tile.ready && !tile.rendering) render(tile)
        }
        // tiles without a bitmap are not in the budget: forget the ones far from the view (boards are endless)
        if (tiles.size > wanted.size * 4 + 64) {
            val keep = wanted.toHashSet()
            tiles.values.removeAll { it.bitmap == null && !it.rendering && it.key !in keep }
        }
    }

    private fun render(tile: Tile) {
        val gen = tile.gen
        val rect = TileMath.rect(tile.key)
        val strokes = strokesFor(rect)
        if (strokes.isEmpty()) {
            install(tile, gen, null)
            readyRev++ // draw again: the frame that scheduled this still counted the tile as missing
            return
        }
        tile.rendering = true
        val ppw = TileMath.pixelsPerWorld(tile.key.level)
        scope.launch {
            try {
                // converting strokes for the renderer costs about as much as drawing them: spread it over the cores
                withContext(Dispatchers.Default) {
                    val part = (strokes.size + 3) / 4
                    strokes.chunked(part).forEach { p -> launch { p.forEach { layer.renderable(it) } } }
                }
                val bitmap = withContext(renderThread) {
                    if (tile.fullGen > gen) return@withContext null // changed again while queued
                    Bitmap.createBitmap(TileMath.TILE_PX, TileMath.TILE_PX, Bitmap.Config.ARGB_8888).also {
                        InkRaster.draw(Canvas(it), strokes, ppw, rect.left, rect.top, renderer = tileRenderer, ink = layer::renderable)
                        it.prepareToDraw()
                    }
                }
                // a later change other than a stroke on top makes this picture wrong; strokes on top are drawn over it
                if (bitmap != null && tiles[tile.key] === tile && tile.fullGen <= gen) install(tile, gen, bitmap)
            } finally {
                tile.rendering = false
                readyRev++ // draw again: shows the picture, or schedules the next render
            }
        }
    }

    private fun install(tile: Tile, gen: Int, bitmap: Bitmap?) {
        tile.bitmap = bitmap
        tile.imageGen = gen
        tile.onTop.removeAll { it.first <= gen }
        if (bitmap == null) {
            budget.remove(tile.key)
            return
        }
        for (k in budget.put(tile.key, TILE_BYTES)) tiles.remove(k)
    }

    /** Marks the tiles over [rect] (world units) dirty: they draw directly until rendered again. */
    fun invalidate(rect: InkRect) {
        for (t in tiles.values) if (TileMath.rect(t.key).intersects(rect)) {
            t.gen++
            t.fullGen = t.gen
        }
    }

    /** Keeps [strokes] out of the tiles and draws them directly on top (a live selection). */
    fun exclude(strokes: Collection<InkStroke>) {
        val next = Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(strokes) }
        if (next == excluded) return
        val changed = excluded.filter { it !in next } + next.filter { it !in excluded }
        excluded = if (next.isEmpty()) emptySet() else next
        for (s in changed) layer.paintedArea(s)?.let(::invalidate)
    }

    /** True when every tile of the current level under [stroke] is drawn from a fresh bitmap. */
    fun isReady(stroke: InkStroke): Boolean {
        val l = level ?: return false
        val area = layer.paintedArea(stroke) ?: return false
        return TileMath.covering(area, l).all { tiles[it]?.ready == true }
    }

    /** Drops every bitmap; the visible tiles render again on the next frame. */
    fun clear() {
        tiles.clear()
        budget.clear()
        previousLevel = null
        readyRev++
    }

    /** The canvas left the screen: drops the bitmaps and stops listening to the layer. */
    fun dispose() {
        disposed = true
        clear()
        layer.removeChangeListener(listener)
        live.remove(this)
    }

    companion object {
        private const val TILE_BYTES = TileMath.TILE_PX.toLong() * TileMath.TILE_PX * 4
        /** Beyond this many tiles under the view a pinch switches level at once instead of scaling. */
        private const val MAX_SCALED_TILES = 48
        /** How many strokes of tiles without a picture of ink yet one frame may draw directly. */
        private const val MAX_DIRECT_STROKES = 300

        @OptIn(ExperimentalCoroutinesApi::class)
        private val renderThread = Dispatchers.Default.limitedParallelism(1)

        /** Live instances (main thread), weakly held. */
        private val live = WeakHashMap<InkTiles, Unit>()

        /** All live tile caches, for tests. */
        fun live(): List<InkTiles> = live.keys.toList()

        /** Frees every tile bitmap in the app (low memory). */
        fun trimAll() = live.keys.toList().forEach { it.clear() }

        /** 64 MB, or a quarter of the heap limit (the memory class) when that is smaller. */
        private fun budgetBytes(): Long = minOf(64L shl 20, Runtime.getRuntime().maxMemory() / 4)
    }
}
