package app.wanki.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.ink.strokes.Stroke
import app.wanki.ink.BrushKind
import app.wanki.ink.InkAffine
import app.wanki.ink.InkCodec
import app.wanki.ink.InkDrawing
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.ink.SpatialGrid
import app.wanki.ink.StrokeEraser
import java.util.IdentityHashMap

/**
 * The strokes of one canvas (world coordinates) with undo/redo. Renderable androidx.ink strokes
 * and bounds are cached per stroke, a [SpatialGrid] answers [strokesIn], and encoded chunks are cached
 * per stroke for incremental saves. Every edit of [strokes] goes through the private helpers
 * ([insertAt], [removeIndex], [setAt], [resetStrokes]) so the grid and [strokesRev] stay in step. [version] changes once per finished edit — one stroke, one
 * eraser drag, one lasso move, or a cancelled drag that put strokes back — so autosave and
 * recognition run once per gesture.
 */
class InkLayer(initial: List<InkStroke> = emptyList()) {
    val strokes = mutableStateListOf<InkStroke>().apply { addAll(initial) }

    /** A plain copy of [strokes], kept in step by the edit helpers: walking a state list is slow on big pages. */
    private val plain = ArrayList(initial)

    var version by mutableIntStateOf(0)
        private set

    /** Bumped on every change to [strokes] (also mid-gesture), for tiles and incremental saves. */
    var strokesRev by mutableIntStateOf(0)
        private set

    private val render = IdentityHashMap<InkStroke, Stroke?>() // guarded by itself: tiles convert off main
    private val boundsCache = IdentityHashMap<InkStroke, InkRect?>()
    private val grid = SpatialGrid<InkStroke>()
    private val chunks = IdentityHashMap<InkStroke, ByteArray>()

    /** Hears every change to [strokes] on the main thread (the ink tiles). */
    interface ChangeListener {
        /** Something changed inside [area] (world units, brush width included). */
        fun changed(area: InkRect)

        /** [stroke] was added on top of all others; it covers [area]. */
        fun appended(stroke: InkStroke, area: InkRect)
    }

    private val changeListeners = ArrayList<ChangeListener>()

    init {
        initial.forEach(::indexStroke)
    }

    fun addChangeListener(l: ChangeListener) { changeListeners += l }
    fun removeChangeListener(l: ChangeListener) { changeListeners -= l }
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** Uptime of the first stroke since the last [clear]; stroke times are relative to it. */
    internal var timeBaseUptime = -1L

    private sealed interface Edit {
        data class Added(val strokes: List<InkStroke>) : Edit
        data class Removed(val items: List<Pair<Int, InkStroke>>) : Edit
        data class Replaced(val before: List<InkStroke>, val after: List<InkStroke>) : Edit
    }

    /** Lasso being drawn (world coordinates), and the strokes it selected (always strokes the layer has, in layer order). */
    var lasso by mutableStateOf<List<Offset>?>(null)
    val selection = mutableStateListOf<InkStroke>()

    val isEmpty: Boolean get() = strokes.isEmpty()
    val hasSelection: Boolean get() = selection.isNotEmpty()

    /** Selects strokes with most of their points inside the polygon. Main thread (asks the grid). */
    fun selectInside(polygon: List<Offset>) {
        selection.clear()
        if (polygon.size < 3) return
        val box = InkRect(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })
        val near = grid.query(box)
        if (near.isEmpty()) return
        selection.addAll(inLayerOrder(near.filter { s -> s.points.isNotEmpty() && s.points.count { inside(polygon, it.x, it.y) } >= s.points.size * 0.6 }))
    }

    /**
     * Selects the strokes whose bounds lie mostly (more than half) outside [bounds], e.g. past the edge of a page that
     * was turned to portrait. Returns how many.
     */
    fun selectOutside(bounds: InkRect): Int {
        val found = outside(bounds)
        selection.clear()
        selection.addAll(found)
        return found.size
    }

    /** The strokes [selectOutside] would select, in layer order. */
    fun outside(bounds: InkRect): List<InkStroke> = plain.filter { s -> bounds(s)?.let { insideShare(it, bounds) < 0.5f } == true }

    /** The share of [r] inside [page]; a side of zero length counts as inside or out by where it lies. */
    private fun insideShare(r: InkRect, page: InkRect): Float {
        fun share(a: Float, b: Float, lo: Float, hi: Float): Float =
            if (b - a <= 0f) (if (a in lo..hi) 1f else 0f) else (minOf(b, hi) - maxOf(a, lo)).coerceAtLeast(0f) / (b - a)
        return share(r.left, r.right, page.left, page.right) * share(r.top, r.bottom, page.top, page.bottom)
    }

    fun clearSelection() = selection.clear()

    fun selectionBounds(): InkRect? = selection.mapNotNull { bounds(it) }.reduceOrNull { a, b -> a.union(b) }

    /** Removes strokes by index (undoable), e.g. after a scratch-out gesture. */
    fun removeAt(indices: List<Int>) {
        val hit = indices.sorted().filter { it in strokes.indices }.map { it to strokes[it] }
        if (hit.isEmpty()) return
        hit.asReversed().forEach { removeIndex(it.first) }
        push(Edit.Removed(hit))
        fixSelection()
    }

    // --- lasso transform: the strokes stay as they are while the drag runs, [liveTransform] shows where they go

    private var transformBefore: List<InkStroke>? = null
    private var transformIndices: List<Int> = emptyList()

    /**
     * Where the selected strokes are being dragged (null when no transform runs). The strokes themselves change only
     * at [endTransform], so a drag makes no edits (and no tile, grid or index work) per frame: the canvas draws the
     * selection through this map.
     */
    var liveTransform by mutableStateOf<InkAffine?>(null)
        private set

    /** Starts scaling, turning or moving the selection; [endTransform] makes the whole drag one undo step. */
    fun beginTransform() {
        if (selection.isEmpty()) return
        transformBefore = plain.toList()
        transformIndices = indicesOf(selection)
        liveTransform = InkAffine.identity
    }

    /** The selection as it was at [beginTransform], mapped by [m] (not added to earlier calls). */
    fun transformSelection(m: InkAffine) {
        if (transformBefore == null) return
        liveTransform = m
    }

    /** Applies the drag to the strokes, as one undo step; the transformed strokes stay selected. */
    fun endTransform() {
        val before = transformBefore ?: return
        val m = liveTransform ?: InkAffine.identity
        transformBefore = null
        liveTransform = null
        if (m.isIdentity) return
        val moved = transformIndices.map { i -> m.apply(plain[i]).also { setAt(i, it) } }
        push(Edit.Replaced(before, plain.toList()))
        selection.clear()
        selection.addAll(moved)
    }

    /** Drops the drag (it turned out to be a pinch or a tap): the strokes never changed, so nothing needs saving. */
    fun cancelTransform() {
        if (transformBefore == null) return
        transformBefore = null
        liveTransform = null
    }

    fun beginMove() = beginTransform()

    /** Shifts the selection further by (dx, dy) during a drag. */
    fun moveSelectionBy(dx: Float, dy: Float) {
        if (transformBefore == null || (dx == 0f && dy == 0f)) return
        transformSelection((liveTransform ?: InkAffine.identity).then(InkAffine.translate(dx, dy)))
    }

    fun endMove() = endTransform()
    fun cancelMove() = cancelTransform()

    /** Indices of [subset] in the layer, ascending. */
    private fun indicesOf(subset: Collection<InkStroke>): List<Int> {
        val order = order()
        return subset.mapNotNull { order[it] }.sorted()
    }

    /** Replaces each selected stroke by [change] of it, as one undo step; the results stay selected. */
    private fun changeSelection(change: (InkStroke) -> InkStroke) {
        val indices = indicesOf(selection)
        if (indices.isEmpty()) return
        val before = plain.toList()
        var any = false
        val after = indices.map { i ->
            val old = plain[i]
            val new = change(old)
            if (new != old) { setAt(i, new); any = true }
            plain[i]
        }
        if (!any) return
        push(Edit.Replaced(before, plain.toList()))
        selection.clear()
        selection.addAll(after)
    }

    /** Gives the selected strokes [color]; highlighter strokes keep their translucency (alpha 0x66). */
    fun recolorSelection(color: Int) = changeSelection { s ->
        val c = if (s.brush.kind == BrushKind.HIGHLIGHTER) (color and 0x00FFFFFF) or 0x66000000 else color
        if (c == s.brush.color) s else s.copy(brush = s.brush.copy(color = c))
    }

    /** The selected strokes in layer order, relative to the top-left corner of their bounds. */
    fun copySelection(): InkClip {
        val list = plainSelection()
        val b = list.mapNotNull { bounds(it) }.reduceOrNull { x, y -> x.union(y) } ?: return InkClip(emptyList())
        val shift = InkAffine.translate(-b.left, -b.top)
        return InkClip(list.map(shift::apply))
    }

    /** Adds [clip] on top with its centre at ([centerX], [centerY]), as one undo step; the pasted strokes become the selection. */
    fun paste(clip: InkClip, centerX: Float, centerY: Float) {
        val b = clip.bounds() ?: return
        addSelected(clip.strokes.map(InkAffine.translate(centerX - (b.left + b.right) / 2, centerY - (b.top + b.bottom) / 2)::apply))
    }

    /** Adds a copy of the selection 24 units right and down, as one undo step; the copy becomes the selection. */
    fun duplicateSelection() {
        addSelected(plainSelection().map(InkAffine.translate(DUPLICATE_OFFSET, DUPLICATE_OFFSET)::apply))
    }

    private fun addSelected(list: List<InkStroke>) {
        if (list.isEmpty()) return
        list.forEach { insertAt(strokes.size, it) }
        push(Edit.Added(list))
        selection.clear()
        selection.addAll(list)
    }

    /** Removes the selection as one undo step and returns it as a clip (see [copySelection]). */
    fun takeSelection(): InkClip {
        val clip = copySelection()
        deleteSelection()
        return clip
    }

    /** The undo step on top, compared by identity: tells whether the next [undo] would revert a given edit. */
    val undoTop: Any? get() = undoStack.lastOrNull()

    private fun plainSelection(): List<InkStroke> = indicesOf(selection).map { plain[it] }

    /** Removes the selected strokes (undoable). */
    fun deleteSelection() {
        val hit = strokes.withIndex().filter { (_, s) -> selection.any { it === s } }
        if (hit.isEmpty()) return
        hit.asReversed().forEach { removeIndex(it.index) }
        push(Edit.Removed(hit.map { it.index to it.value }))
        selection.clear()
    }

    /**
     * After an edit that may have replaced strokes (erase, undo, redo, reload): a selection may only hold strokes the
     * layer still has. When the list kept its length (a transform or recolour undone), selected strokes are followed
     * to the same places in [before] → [after].
     */
    private fun fixSelection(before: List<InkStroke>? = null, after: List<InkStroke>? = null) {
        if (selection.isEmpty()) return
        val order = order()
        if (selection.all { it in order }) return
        val mapped = if (before != null && after != null && before.size == after.size) {
            val at = IdentityHashMap<InkStroke, Int>().apply { before.forEachIndexed { i, s -> put(s, i) } }
            selection.map { s -> if (s in order) s else at[s]?.let { after[it] } }
        } else selection.toList()
        val keep = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>())
        mapped.forEach { if (it != null && it in order) keep += it }
        selection.clear()
        selection.addAll(inLayerOrder(keep))
    }

    private fun inside(poly: List<Offset>, x: Float, y: Float): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }
    val canUndo: Boolean get() = version >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = version >= 0 && redoStack.isNotEmpty()

    fun drawing() = InkDrawing(strokes.toList())

    /** Strokes converted before the layer got them ([adoptConverted]); entries move to the cache when used. */
    @Volatile private var convertedAhead: MutableMap<InkStroke, Stroke>? = null

    /**
     * Uses strokes converted elsewhere (a page's ink read ahead) instead of converting them again. [converted] may still
     * be filling up on other threads: it must be a synchronized map. Dropped by the next [replaceAll].
     */
    fun adoptConverted(converted: MutableMap<InkStroke, Stroke>) {
        convertedAhead = converted
    }

    /** The androidx.ink stroke for [s], converted once per stroke instance. Thread-safe. */
    fun renderable(s: InkStroke): Stroke? {
        synchronized(render) { if (render.containsKey(s)) return render[s] }
        val made = convertedAhead?.remove(s) ?: StrokeConversion.toInk(s)
        return synchronized(render) { if (render.containsKey(s)) render[s] else made.also { render[s] = it } }
    }
    fun bounds(s: InkStroke): InkRect? = boundsCache.getOrPut(s) { s.bounds() }

    /** Bounds computed off the main thread (a page's ink read ahead), so [replaceAll] need not compute them. Main thread. */
    fun adoptBounds(bounds: Map<InkStroke, InkRect?>) = boundsCache.putAll(bounds)

    /**
     * Strokes that may paint inside [rect] (grid cells over their bounds plus half the brush, a superset), in layer order.
     * Main thread only: it reads the Compose-state stroke list and the [SpatialGrid], which the editing
     * functions mutate on the main thread without locking. Off-thread drawing must work from a copy.
     */
    fun strokesIn(rect: InkRect): List<InkStroke> {
        val found = grid.query(rect)
        if (found.isEmpty()) return emptyList()
        if (found.size > plain.size / 8) return plain.filter { it in found }
        // a small part of a big page (a tile): sort the hits by their place instead of walking every stroke
        val order = order()
        return found.sortedBy { order[it] ?: Int.MAX_VALUE }
    }

    /** Those of [subset] still in the layer, in layer order. Main thread only. */
    fun inLayerOrder(subset: Collection<InkStroke>): List<InkStroke> {
        if (subset.isEmpty()) return emptyList()
        val order = order()
        return subset.filter { it in order }.sortedBy { order.getValue(it) }
    }

    private var orderMap = IdentityHashMap<InkStroke, Int>()
    private var orderRev = -1

    /** Index of every stroke in [strokes], rebuilt once per [strokesRev]. */
    private fun order(): IdentityHashMap<InkStroke, Int> {
        if (orderRev != strokesRev) {
            val m = IdentityHashMap<InkStroke, Int>(plain.size * 2)
            plain.forEachIndexed { i, s -> m[s] = i }
            orderMap = m
            orderRev = strokesRev
        }
        return orderMap
    }

    /** The encoded chunk of [s] (see [InkCodec.encodeStroke]), made once per stroke instance. Thread-safe. */
    fun chunk(s: InkStroke): ByteArray {
        synchronized(chunks) { chunks[s] }?.let { return it }
        val made = InkCodec.encodeStroke(s)
        return synchronized(chunks) { chunks.getOrPut(s) { made } }
    }

    fun strokeKey(s: InkStroke): Long = InkCodec.strokeKey(chunk(s))

    /** The whole layer as an ink blob, or null when empty. Takes the stroke list now, on the caller's thread. */
    fun encodeSnapshot(): ByteArray? = snapshotEncoder()()

    /**
     * Takes the stroke list now (call on the thread that edits the layer); the returned function
     * makes the missing chunks and joins them, and is safe to run on another thread.
     */
    fun snapshotEncoder(): () -> ByteArray? {
        val list = strokes.toList()
        return { if (list.isEmpty()) null else InkCodec.assemble(list.map(::chunk)) }
    }

    /** Like [snapshotEncoder], for the layer without [left] (strokes moved to another page before they are taken). */
    fun snapshotEncoderWithout(left: Collection<InkStroke>): () -> ByteArray? {
        val gone = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(left) }
        val list = plain.filter { it !in gone }
        return { if (list.isEmpty()) null else InkCodec.assemble(list.map(::chunk)) }
    }

    /**
     * Removes one stroke per entry of [keys] ([strokeKey]s; identical strokes share one, so this is a multiset), the
     * topmost first, as one undo step. Returns how many were removed. Main thread.
     */
    fun removeByKeys(keys: List<Long>): Int {
        val left = HashMap<Long, Int>().apply { keys.forEach { merge(it, 1, Int::plus) } }
        val hit = ArrayList<Int>()
        for (i in plain.indices.reversed()) {
            val k = strokeKey(plain[i])
            val n = left[k] ?: 0
            if (n > 0) { left[k] = n - 1; hit += i }
        }
        removeAt(hit)
        return hit.size
    }

    fun add(s: InkStroke) {
        insertAt(strokes.size, s)
        push(Edit.Added(listOf(s)))
    }

    fun eraseAt(x: Float, y: Float, radius: Float): Boolean = eraseAlong(x, y, x, y, radius, EraserMode.WHOLE)

    /**
     * Erases under the capsule a–b (the eraser moved from a to b, radius [radius] in world units): whole strokes
     * touched ([EraserMode.WHOLE]) or only their parts under it ([EraserMode.PARTIAL], see [StrokeEraser]; a cut
     * stroke becomes its pieces at the same place in the stacking order). In an eraser drag ([beginErase]) it
     * makes no undo step of its own. True when anything changed. Main thread.
     */
    fun eraseAlong(ax: Float, ay: Float, bx: Float, by: Float, radius: Float, mode: EraserMode): Boolean {
        // the grid holds each stroke's painted area (bounds plus half the brush), so the swept bounds widened by the radius find every candidate
        val near = grid.query(InkRect(minOf(ax, bx) - radius, minOf(ay, by) - radius, maxOf(ax, bx) + radius, maxOf(ay, by) + radius))
        if (near.isEmpty()) return false
        val cuts = ArrayList<Pair<Int, List<InkStroke>>>()
        for (s in near) {
            val b = bounds(s) ?: continue
            val reach = radius + s.brush.size / 2
            if (maxOf(ax, bx) < b.left - reach || minOf(ax, bx) > b.right + reach ||
                maxOf(ay, by) < b.top - reach || minOf(ay, by) > b.bottom + reach) continue
            val pieces = if (mode == EraserMode.PARTIAL) StrokeEraser.cut(s, ax, ay, bx, by, radius)
            else if (StrokeEraser.touches(s, ax, ay, bx, by, radius)) emptyList() else null
            if (pieces != null) cuts += plain.indexOfFirst { it === s } to pieces
        }
        if (cuts.isEmpty()) return false
        val before = if (eraseBefore == null && mode == EraserMode.PARTIAL) plain.toList() else null
        val removed = ArrayList<Pair<Int, InkStroke>>()
        // from the back, so earlier indices stay valid
        for ((i, pieces) in cuts.sortedByDescending { it.first }) {
            val old = plain[i]
            removed += i to old
            if (pieces.isEmpty()) removeIndex(i)
            else {
                setAt(i, pieces[0])
                for (k in 1 until pieces.size) insertAt(i + k, pieces[k])
            }
        }
        when {
            eraseBefore != null -> eraseChanged = true
            before != null -> push(Edit.Replaced(before, plain.toList()))
            else -> push(Edit.Removed(removed.reversed()))
        }
        fixSelection()
        return true
    }

    private var eraseBefore: List<InkStroke>? = null
    private var eraseChanged = false

    /**
     * Starts an eraser drag: [eraseAlong] then removes or cuts strokes live without undo steps or [version]
     * changes, and [endErase] records the whole drag as one undo step.
     */
    fun beginErase() {
        eraseBefore = strokes.toList()
        eraseChanged = false
    }

    /** Ends the eraser drag; true when it removed anything (one undo step was pushed). */
    fun endErase(): Boolean {
        val before = eraseBefore ?: return false
        eraseBefore = null
        if (!eraseChanged) return false
        push(Edit.Replaced(before, strokes.toList()))
        return true
    }

    /** Drops the eraser drag and puts back what it removed (e.g. it turned out to be a pinch). */
    fun cancelErase() {
        val before = eraseBefore ?: return
        eraseBefore = null
        if (eraseChanged) {
            resetStrokes(before)
            fixSelection()
            // a save during the drag may have stored the half-erased strokes: save the restored ones again
            version++
        }
    }

    fun replaceAll(newStrokes: List<InkStroke>, undoable: Boolean = true) {
        val before = strokes.toList()
        eraseBefore = null // a reload or replace ends any eraser drag or move in progress
        transformBefore = null
        liveTransform = null
        convertedAhead = null
        resetStrokes(newStrokes)
        fixSelection()
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(strokes) }
        synchronized(chunks) { chunks.keys.retainAll(live) }
        synchronized(render) { render.keys.retainAll(live) }
        if (undoable) push(Edit.Replaced(before, newStrokes)) else {
            undoStack.clear(); redoStack.clear(); version++
        }
    }

    fun clear() {
        if (strokes.isNotEmpty()) replaceAll(emptyList())
        timeBaseUptime = -1L
    }

    /** An eraser drag or selection transform holds a snapshot; undo/redo now would make it stale. */
    private val gestureOpen: Boolean get() = eraseBefore != null || transformBefore != null

    fun undo() {
        if (gestureOpen) return
        val e = undoStack.removeLastOrNull() ?: return
        when (e) {
            is Edit.Added -> e.strokes.forEach { s -> removeIndex(strokes.indexOfLast { it === s }) }
            is Edit.Removed -> e.items.sortedBy { it.first }.forEach { (i, s) -> insertAt(i.coerceAtMost(strokes.size), s) }
            is Edit.Replaced -> resetStrokes(e.before)
        }
        if (e is Edit.Replaced) fixSelection(e.after, e.before) else fixSelection()
        redoStack.addLast(e)
        version++
    }

    fun redo() {
        if (gestureOpen) return
        val e = redoStack.removeLastOrNull() ?: return
        when (e) {
            is Edit.Added -> e.strokes.forEach { insertAt(strokes.size, it) }
            is Edit.Removed -> e.items.sortedByDescending { it.first }.forEach { (_, s) ->
                strokes.indexOfFirst { it === s }.takeIf { it >= 0 }?.let(::removeIndex)
            }
            is Edit.Replaced -> resetStrokes(e.after)
        }
        if (e is Edit.Replaced) fixSelection(e.before, e.after) else fixSelection()
        undoStack.addLast(e)
        version++
    }

    /** The grid holds where a stroke paints (bounds plus half the brush), so a tile finds every stroke reaching into it. */
    private fun indexStroke(s: InkStroke) {
        paintedArea(s)?.let { grid.insert(s, it) }
    }

    /** Tells the listeners which area [s] covers (it was added, removed or moved in the stacking order). */
    private fun changed(s: InkStroke, appended: Boolean = false) {
        if (changeListeners.isEmpty()) return
        val r = paintedArea(s) ?: return
        changeListeners.toList().forEach { if (appended) it.appended(s, r) else it.changed(r) }
    }

    /** Where [s] may paint: its bounds widened by half the brush (and a little for antialiasing). */
    fun paintedArea(s: InkStroke): InkRect? {
        val b = bounds(s) ?: return null
        val pad = s.brush.size / 2 + 2f
        return InkRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    private fun insertAt(i: Int, s: InkStroke) {
        strokes.add(i, s)
        plain.add(i, s)
        indexStroke(s)
        strokesRev++
        changed(s, appended = i == strokes.lastIndex)
    }

    private fun removeIndex(i: Int) {
        if (i !in strokes.indices) return
        val s = strokes.removeAt(i)
        plain.removeAt(i)
        grid.remove(s)
        strokesRev++
        changed(s)
    }

    private fun setAt(i: Int, s: InkStroke) {
        val old = strokes[i]
        grid.remove(old)
        strokes[i] = s
        plain[i] = s
        indexStroke(s)
        strokesRev++
        changed(old)
        changed(s)
    }

    /** Replaces the whole list; the grid is rebuilt. */
    private fun resetStrokes(list: List<InkStroke>) {
        val copy = list.toList() // [list] may be the live stroke list
        val old = if (changeListeners.isEmpty()) emptyList() else strokes.toList()
        strokes.clear()
        strokes.addAll(copy)
        plain.clear()
        plain.addAll(copy)
        grid.clear()
        copy.forEach(::indexStroke)
        strokesRev++
        if (changeListeners.isNotEmpty()) changedBetween(old, copy)
    }

    /** Reports strokes that appear in only one of the lists, or whose place in the stacking order differs. */
    private fun changedBetween(old: List<InkStroke>, new: List<InkStroke>) {
        val inOld = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(old) }
        if (old.isEmpty()) {
            // a page's ink arriving: one area for all of it, not thousands of reports
            var area: InkRect? = null
            for (s in new) paintedArea(s)?.let { r ->
                area = area?.let { InkRect(minOf(it.left, r.left), minOf(it.top, r.top), maxOf(it.right, r.right), maxOf(it.bottom, r.bottom)) } ?: r
            }
            area?.let { a -> changeListeners.toList().forEach { it.changed(a) } }
            return
        }
        val inNew = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(new) }
        old.forEach { if (it !in inNew) changed(it) }
        new.forEach { if (it !in inOld) changed(it) }
        // strokes common to both, in order: a different sequence means overlaps may stack differently
        val keptOld = old.filter { it in inNew }
        val keptNew = new.filter { it in inOld }
        for (i in keptOld.indices) if (keptOld[i] !== keptNew[i]) { changed(keptOld[i]); changed(keptNew[i]) }
    }

    private companion object {
        const val DUPLICATE_OFFSET = 24f
    }

    private fun push(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        version++
    }
}
