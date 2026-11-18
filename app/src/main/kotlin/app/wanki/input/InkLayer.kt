package app.wanki.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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

/** A page's strokes with their bounds and grid, made by [InkLayer.prepare] off the main thread; adopt it once. */
class LoadedInk internal constructor(
    val strokes: List<InkStroke>,
    internal val bounds: IdentityHashMap<InkStroke, InkRect?>,
    internal val grid: SpatialGrid<InkStroke>,
    internal val area: InkRect?,
)

/**
 * The strokes of one canvas (world coordinates) with undo/redo. Renderable androidx.ink strokes
 * and bounds are cached per stroke, a [SpatialGrid] answers [strokesIn], and encoded chunks are cached
 * per stroke for incremental saves. Every edit of [strokes] goes through the private helpers
 * ([insertAt], [removeIndex], [setAt], [resetStrokes]) so the grid and [strokesRev] stay in step. [version] changes once per finished edit — one stroke, one
 * eraser drag, one lasso move, or a cancelled drag that put strokes back — so autosave and
 * recognition run once per gesture.
 *
 * Page [objects] (text blocks and pictures, drawn under the ink) share the undo stack and the lasso selection: every
 * object edit goes through [applyObjects], which bumps [objectsRev]; an edit of strokes and objects together (a mixed
 * transform, paste or delete) is one undo step. Objects are followed by id, which no transform changes.
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
    /** Replaced whole only by [adoptLoaded] (a grid built off the main thread). */
    private var grid = SpatialGrid<InkStroke>()
    private val chunks = IdentityHashMap<InkStroke, ByteArray>()

    /** Hears every change to [strokes] on the main thread (the ink tiles). */
    interface ChangeListener {
        /** Something changed inside [area] (world units, brush width included). */
        fun changed(area: InkRect)

        /** [stroke] was added on top of all others; it covers [area]. */
        fun appended(stroke: InkStroke, area: InkRect)
    }

    private val changeListeners = ArrayList<ChangeListener>()

    /** Text blocks and pictures in draw order ([PageObject.ORDER]); edit them only through this layer. */
    val objects: SnapshotStateList<PageObject> = mutableStateListOf()

    /** Ids of the selected [objects]. */
    val selectedObjects: SnapshotStateList<String> = mutableStateListOf()

    /** Bumped on every change to [objects] (saves store them only when it moved). */
    var objectsRev by mutableIntStateOf(0)
        private set

    init {
        initial.forEach(::indexStroke)
    }

    fun addChangeListener(l: ChangeListener) { changeListeners += l }
    fun removeChangeListener(l: ChangeListener) { changeListeners -= l }
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** Uptime of the first stroke since the last [clear]; stroke times are relative to it. */
    internal var timeBaseUptime = -1L

    /** One object added (before null), removed (after null) or changed. */
    private data class ObjectChange(val before: PageObject?, val after: PageObject?)

    /** Every step may also change [objects] ([objs], applied after its strokes). */
    private sealed interface Edit {
        val objs: List<ObjectChange>

        data class Added(val strokes: List<InkStroke>, override val objs: List<ObjectChange> = emptyList()) : Edit
        data class Removed(val items: List<Pair<Int, InkStroke>>, override val objs: List<ObjectChange> = emptyList()) : Edit
        /** [changed] pairs each changed stroke (before → after) of a transform or recolour, so undo and redo keep it selected. */
        data class Replaced(
            val before: List<InkStroke>,
            val after: List<InkStroke>,
            val changed: List<Pair<InkStroke, InkStroke>> = emptyList(),
            override val objs: List<ObjectChange> = emptyList(),
        ) : Edit
        /** Objects only. */
        data class Objects(override val objs: List<ObjectChange>) : Edit
    }

    /** Lasso being drawn (world coordinates), and the strokes it selected (always strokes the layer has, in layer order). */
    var lasso by mutableStateOf<List<Offset>?>(null)
    val selection = mutableStateListOf<InkStroke>()

    val isEmpty: Boolean get() = strokes.isEmpty()
    val hasSelection: Boolean get() = selection.isNotEmpty() || selectedObjects.isNotEmpty()

    /**
     * Selects strokes with most of their points inside the polygon, and objects whose bounds centre is inside it.
     * Main thread (asks the grid).
     */
    fun selectInside(polygon: List<Offset>) {
        selection.clear()
        selectedObjects.clear()
        if (polygon.size < 3) return
        selectedObjects.addAll(objects.filter { o -> o.bounds().let { inside(polygon, (it.left + it.right) / 2, (it.top + it.bottom) / 2) } }.map { it.id })
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
        val objs = objectsOutside(bounds)
        selection.clear()
        selection.addAll(found)
        selectedObjects.clear()
        selectedObjects.addAll(objs.map { it.id })
        return found.size + objs.size
    }

    /** The strokes [selectOutside] would select, in layer order. */
    fun outside(bounds: InkRect): List<InkStroke> = plain.filter { s -> bounds(s)?.let { insideShare(it, bounds) < 0.5f } == true }

    /** The objects [selectOutside] would select. */
    fun objectsOutside(bounds: InkRect): List<PageObject> = objects.filter { insideShare(it.bounds(), bounds) < 0.5f }

    /** The share of [r] inside [page]; a side of zero length counts as inside or out by where it lies. */
    private fun insideShare(r: InkRect, page: InkRect): Float {
        fun share(a: Float, b: Float, lo: Float, hi: Float): Float =
            if (b - a <= 0f) (if (a in lo..hi) 1f else 0f) else (minOf(b, hi) - maxOf(a, lo)).coerceAtLeast(0f) / (b - a)
        return share(r.left, r.right, page.left, page.right) * share(r.top, r.bottom, page.top, page.bottom)
    }

    fun clearSelection() {
        selection.clear()
        selectedObjects.clear()
    }

    /** Around the selected strokes and objects. */
    fun selectionBounds(): InkRect? =
        (selection.mapNotNull { bounds(it) } + selectedObjectList().map { it.bounds() }).reduceOrNull { a, b -> a.union(b) }

    /** The selected objects, in draw order. */
    fun selectedObjectList(): List<PageObject> {
        if (selectedObjects.isEmpty()) return emptyList()
        val ids = selectedObjects.toHashSet()
        return objects.filter { it.id in ids }
    }

    // --- objects: each public edit is one undo step

    /** The z for an object put on top of all others. */
    fun nextZ(): Int = (objects.maxOfOrNull { it.z } ?: -1) + 1

    /** Adds [o] (its id must be new to the layer), as one undo step. */
    fun addObject(o: PageObject) {
        if (objects.any { it.id == o.id }) return
        pushObjects(listOf(ObjectChange(null, o)))
    }

    /** Replaces the object with [o]'s id by [o], as one undo step; nothing when it is gone or unchanged. */
    fun updateObject(o: PageObject) {
        val old = objects.firstOrNull { it.id == o.id } ?: return
        if (old == o) return
        pushObjects(listOf(ObjectChange(old, o)))
    }

    /** Removes the objects with [ids], as one undo step. */
    fun removeObjects(ids: Collection<String>) {
        val set = ids.toHashSet()
        pushObjects(objects.filter { it.id in set }.map { ObjectChange(it, null) })
    }

    /** The stored objects of a page being opened: not an undo step. */
    fun resetObjects(list: List<PageObject>) {
        objects.clear()
        objects.addAll(list.sortedWith(PageObject.ORDER))
        selectedObjects.clear()
        objectsRev++
    }

    private fun pushObjects(changes: List<ObjectChange>) {
        if (changes.isEmpty()) return
        applyObjects(changes, forward = true)
        push(Edit.Objects(changes))
        fixObjectSelection()
    }

    /** Applies [changes] (or reverts them, last first), keeping [objects] in draw order. */
    private fun applyObjects(changes: List<ObjectChange>, forward: Boolean) {
        if (changes.isEmpty()) return
        for (c in if (forward) changes else changes.asReversed()) {
            val to = if (forward) c.after else c.before
            val id = (c.before ?: c.after)!!.id
            val i = objects.indexOfFirst { it.id == id }
            if (i >= 0) objects.removeAt(i)
            if (to != null) {
                val at = objects.indexOfFirst { PageObject.ORDER.compare(it, to) > 0 }
                if (at < 0) objects.add(to) else objects.add(at, to)
            }
        }
        objectsRev++
    }

    /** A selection may only hold objects the layer has. */
    private fun fixObjectSelection() {
        if (selectedObjects.isEmpty()) return
        val ids = objects.mapTo(HashSet()) { it.id }
        if (selectedObjects.all { it in ids }) return
        val keep = selectedObjects.filter { it in ids }
        selectedObjects.clear()
        selectedObjects.addAll(keep)
    }

    /** Removes strokes by index (undoable), e.g. after a scratch-out gesture. */
    fun removeAt(indices: List<Int>) {
        val hit = indices.sorted().filter { it in strokes.indices }.map { it to strokes[it] }
        if (hit.isEmpty()) return
        hit.asReversed().forEach { removeIndex(it.first) }
        push(Edit.Removed(hit))
        fixSelection()
    }

    // --- lasso transform: the strokes stay as they are while the drag runs, [liveTransform] shows where they go

    /** The strokes being transformed (by identity, so an edit from another pointer meanwhile cannot shift them); null when none. */
    private var transforming: List<InkStroke>? = null
    /** Ids of the objects being transformed. */
    private var transformingObjects: List<String> = emptyList()

    /**
     * Where the selected strokes are being dragged (null when no transform runs). The strokes themselves change only
     * at [endTransform], so a drag makes no edits (and no tile, grid or index work) per frame: the canvas draws the
     * selection through this map.
     */
    var liveTransform by mutableStateOf<InkAffine?>(null)
        private set

    /** Starts scaling, turning or moving the selection; [endTransform] makes the whole drag one undo step. */
    fun beginTransform() {
        if (!hasSelection) return
        transforming = selection.toList()
        transformingObjects = selectedObjects.toList()
        liveTransform = InkAffine.identity
    }

    /** Whether the object [id] is drawn through [liveTransform] (it is being dragged). */
    fun isTransforming(id: String): Boolean = transforming != null && id in transformingObjects

    /** The selection as it was at [beginTransform], mapped by [m] (not added to earlier calls). */
    fun transformSelection(m: InkAffine) {
        if (transforming == null) return
        liveTransform = m
    }

    /** Applies the drag to the strokes, as one undo step; the transformed strokes stay selected. */
    fun endTransform() {
        val list = transforming ?: return
        val m = liveTransform ?: InkAffine.identity
        val ids = transformingObjects.toHashSet()
        transforming = null
        transformingObjects = emptyList()
        liveTransform = null
        if (m.isIdentity) return
        // where the strokes are now: an erase from another pointer during the drag may have moved or removed some,
        // and the undo step covers only this transform, not that edit
        val indices = indicesOf(list)
        val objs = objects.filter { it.id in ids }.map { ObjectChange(it, it.transformed(m)) }
        if (indices.isEmpty() && objs.isEmpty()) return
        val before = plain.toList()
        val changed = indices.map { i -> val old = plain[i]; old to m.apply(old).also { setAt(i, it) } }
        applyObjects(objs, forward = true)
        push(if (indices.isEmpty()) Edit.Objects(objs) else Edit.Replaced(before, plain.toList(), changed, objs))
        selection.clear()
        selection.addAll(changed.map { it.second })
    }

    /** Drops the drag (it turned out to be a pinch or a tap): the strokes never changed, so nothing needs saving. */
    fun cancelTransform() {
        if (transforming == null) return
        transforming = null
        transformingObjects = emptyList()
        liveTransform = null
    }

    fun beginMove() = beginTransform()

    /** Shifts the selection further by (dx, dy) during a drag. */
    fun moveSelectionBy(dx: Float, dy: Float) {
        if (transforming == null || (dx == 0f && dy == 0f)) return
        transformSelection((liveTransform ?: InkAffine.identity).then(InkAffine.translate(dx, dy)))
    }

    fun endMove() = endTransform()
    fun cancelMove() = cancelTransform()

    /** Indices of [subset] in the layer, ascending. */
    private fun indicesOf(subset: Collection<InkStroke>): List<Int> {
        val order = order()
        return subset.mapNotNull { order[it] }.sorted()
    }

    /**
     * Replaces each selected stroke by [change] of it and each selected object by [changeObject] of it, as one undo
     * step; the results stay selected.
     */
    private fun changeSelection(change: (InkStroke) -> InkStroke, changeObject: (PageObject) -> PageObject) {
        val indices = indicesOf(selection)
        val objs = selectedObjectList().mapNotNull { o -> changeObject(o).takeIf { it != o }?.let { ObjectChange(o, it) } }
        if (indices.isEmpty() && objs.isEmpty()) return
        val before = plain.toList()
        val changed = ArrayList<Pair<InkStroke, InkStroke>>()
        val after = indices.map { i ->
            val old = plain[i]
            val new = change(old)
            if (new != old) { setAt(i, new); changed += old to new }
            plain[i]
        }
        if (changed.isEmpty() && objs.isEmpty()) return
        applyObjects(objs, forward = true)
        push(if (changed.isEmpty()) Edit.Objects(objs) else Edit.Replaced(before, plain.toList(), changed, objs))
        selection.clear()
        selection.addAll(after)
    }

    /** Gives the selected strokes and text blocks [color]; highlighter strokes keep their translucency (alpha 0x66). */
    fun recolorSelection(color: Int) = changeSelection({ s ->
        val c = if (s.brush.kind == BrushKind.HIGHLIGHTER) (color and 0x00FFFFFF) or 0x66000000 else color
        if (c == s.brush.color) s else s.copy(brush = s.brush.copy(color = c))
    }) { o -> if (o.kind == app.wanki.data.PageObjectKind.TEXT) o.copy(color = color) else o }

    /** The selected strokes and objects in layer order, relative to the top-left corner of their bounds. */
    fun copySelection(): InkClip {
        val list = plainSelection()
        val objs = selectedObjectList()
        val b = (list.mapNotNull { bounds(it) } + objs.map { it.bounds() }).reduceOrNull { x, y -> x.union(y) } ?: return InkClip(emptyList())
        val shift = InkAffine.translate(-b.left, -b.top)
        return InkClip(list.map(shift::apply), objs.map { it.transformed(shift) })
    }

    /**
     * Adds [clip] on top with its centre at ([centerX], [centerY]), as one undo step; the pasted strokes and objects
     * (with new ids) become the selection.
     */
    fun paste(clip: InkClip, centerX: Float, centerY: Float) {
        val b = clip.bounds() ?: return
        val m = InkAffine.translate(centerX - (b.left + b.right) / 2, centerY - (b.top + b.bottom) / 2)
        addSelected(clip.strokes.map(m::apply), clip.objects.map { it.transformed(m) })
    }

    /** Adds a copy of the selection 24 units right and down, as one undo step; the copy becomes the selection. */
    fun duplicateSelection() {
        val m = InkAffine.translate(DUPLICATE_OFFSET, DUPLICATE_OFFSET)
        addSelected(plainSelection().map(m::apply), selectedObjectList().map { it.transformed(m) })
    }

    /** Adds [list] on top and [objs] (given new ids, on top of the other objects in their order), selected. */
    private fun addSelected(list: List<InkStroke>, objs: List<PageObject> = emptyList()) {
        if (list.isEmpty() && objs.isEmpty()) return
        list.forEach { insertAt(strokes.size, it) }
        // an eraser drag still open (a paste from the snackbar, the pen's eraser end) must not drop these if it is
        // cancelled; it never touches objects, so they need nothing
        eraseBefore = eraseBefore?.let { it + list }
        val z = nextZ()
        val added = objs.sortedWith(PageObject.ORDER).mapIndexed { i, o -> o.copy(id = PageObject.newId(), z = z + i) }
        val changes = added.map { ObjectChange(null, it) }
        applyObjects(changes, forward = true)
        push(Edit.Added(list, changes))
        selection.clear()
        selection.addAll(list)
        selectedObjects.clear()
        selectedObjects.addAll(added.map { it.id })
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

    /** Removes the selected strokes and objects (one undo step). */
    fun deleteSelection() {
        val hit = indicesOf(selection).map { it to plain[it] }
        val objs = selectedObjectList().map { ObjectChange(it, null) }
        if (hit.isEmpty() && objs.isEmpty()) return
        hit.asReversed().forEach { removeIndex(it.first) }
        dropFromErase(hit.map { it.second })
        applyObjects(objs, forward = true)
        push(Edit.Removed(hit, objs))
        selection.clear()
        selectedObjects.clear()
    }

    /**
     * After an edit that may have replaced strokes (erase, undo, redo, reload): a selection may only hold strokes the
     * layer still has. A stroke changed by a transform or recolour being undone or redone is followed by identity
     * through [follow] (old instance → new); any other stroke that left the layer leaves the selection.
     */
    private fun fixSelection(follow: List<Pair<InkStroke, InkStroke>> = emptyList()) {
        fixObjectSelection()
        if (selection.isEmpty()) return
        val order = order()
        if (selection.all { it in order }) return
        val to = IdentityHashMap<InkStroke, InkStroke>().apply { follow.forEach { (a, b) -> put(a, b) } }
        val mapped = selection.map { s -> if (s in order) s else to[s] }
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

    /** Strokes converted before the layer got them ([adoptConverted]), shared with the thread that converts them. */
    @Volatile private var convertedAhead: MutableMap<InkStroke, Stroke>? = null

    /**
     * Uses strokes converted elsewhere (a page's ink read ahead) instead of converting them again. [converted] may still
     * be filling up on other threads: it must be a synchronized map, and what this layer converts itself goes into it
     * too, so that converter skips those. Dropped by the next [replaceAll] or [adoptLoaded].
     */
    fun adoptConverted(converted: MutableMap<InkStroke, Stroke>) {
        convertedAhead = converted
    }

    /** The androidx.ink stroke for [s], converted once per stroke instance. Thread-safe. */
    fun renderable(s: InkStroke): Stroke? {
        synchronized(render) { if (render.containsKey(s)) return render[s] }
        val ahead = convertedAhead
        val made = ahead?.get(s) ?: StrokeConversion.toInk(s)?.also { ahead?.putIfAbsent(s, it) }
        return synchronized(render) { if (render.containsKey(s)) render[s] else made.also { render[s] = it } }
    }

    /** Whether [s] is converted for drawing already (drawing it now costs no conversion). Thread-safe. */
    fun isConverted(s: InkStroke): Boolean =
        synchronized(render) { render.containsKey(s) } || convertedAhead?.containsKey(s) == true
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
     * topmost first, and the objects with [objectIds], as one undo step. Returns how many strokes and objects were
     * removed. Main thread.
     */
    fun removeByKeys(keys: List<Long>, objectIds: Collection<String> = emptyList()): Int {
        val left = HashMap<Long, Int>().apply { keys.forEach { merge(it, 1, Int::plus) } }
        val hit = ArrayList<Int>()
        for (i in plain.indices.reversed()) {
            val k = strokeKey(plain[i])
            val n = left[k] ?: 0
            if (n > 0) { left[k] = n - 1; hit += i }
        }
        val ids = objectIds.toHashSet()
        val objs = objects.filter { it.id in ids }.map { ObjectChange(it, null) }
        if (hit.isEmpty() && objs.isEmpty()) return 0
        val items = hit.sorted().map { it to plain[it] }
        items.asReversed().forEach { removeIndex(it.first) }
        dropFromErase(items.map { it.second })
        applyObjects(objs, forward = true)
        push(Edit.Removed(items, objs))
        fixSelection()
        return items.size + objs.size
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
     * Strokes taken away as their own undo step (a delete, a move to another page) while an eraser drag is open: a
     * cancelled drag must not put them back, or they would be on the canvas and on the other page (or in the clip).
     */
    private fun dropFromErase(gone: List<InkStroke>) {
        val before = eraseBefore ?: return
        if (gone.isEmpty()) return
        val set = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(gone) }
        eraseBefore = before.filter { it !in set }
    }

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
        transforming = null
        transformingObjects = emptyList()
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

    /**
     * A page's stored ink becomes the layer's, like `replaceAll(ink.strokes, undoable = false)`, but the stroke list,
     * the bounds and the grid were built off the main thread ([prepare]): only the hand-over runs here. Main thread.
     */
    fun adoptLoaded(ink: LoadedInk) {
        eraseBefore = null
        transforming = null
        transformingObjects = emptyList()
        liveTransform = null
        convertedAhead = null
        val old = if (plain.isEmpty()) emptyList() else plain.toList()
        boundsCache.putAll(ink.bounds)
        strokes.clear()
        strokes.addAll(ink.strokes)
        plain.clear()
        plain.addAll(ink.strokes)
        grid = ink.grid
        strokesRev++
        if (changeListeners.isNotEmpty()) {
            if (old.isEmpty()) ink.area?.let { a -> changeListeners.toList().forEach { it.changed(a) } } else changedBetween(old, ink.strokes)
        }
        fixSelection()
        if (old.isNotEmpty()) {
            val live = java.util.Collections.newSetFromMap(IdentityHashMap<InkStroke, Boolean>()).apply { addAll(plain) }
            synchronized(chunks) { chunks.keys.retainAll(live) }
            synchronized(render) { render.keys.retainAll(live) }
        }
        undoStack.clear()
        redoStack.clear()
        version++
    }

    fun clear() {
        if (strokes.isNotEmpty()) replaceAll(emptyList())
        timeBaseUptime = -1L
    }

    /** An eraser drag or selection transform holds a snapshot; undo/redo now would make it stale. */
    private val gestureOpen: Boolean get() = eraseBefore != null || transforming != null

    /** Reverts the last edit; false when nothing was undone (no edit, or an eraser drag or transform is open). */
    fun undo(): Boolean {
        if (gestureOpen) return false
        val e = undoStack.removeLastOrNull() ?: return false
        when (e) {
            is Edit.Added -> e.strokes.forEach { s -> removeIndex(strokes.indexOfLast { it === s }) }
            is Edit.Removed -> e.items.sortedBy { it.first }.forEach { (i, s) -> insertAt(i.coerceAtMost(strokes.size), s) }
            is Edit.Replaced -> resetStrokes(e.before)
            is Edit.Objects -> Unit
        }
        applyObjects(e.objs, forward = false)
        if (e is Edit.Replaced) fixSelection(e.changed.map { (a, b) -> b to a }) else fixSelection()
        redoStack.addLast(e)
        version++
        return true
    }

    /** Re-applies the last undone edit; false when nothing was redone. */
    fun redo(): Boolean {
        if (gestureOpen) return false
        val e = redoStack.removeLastOrNull() ?: return false
        when (e) {
            is Edit.Added -> e.strokes.forEach { insertAt(strokes.size, it) }
            is Edit.Removed -> e.items.sortedByDescending { it.first }.forEach { (_, s) ->
                strokes.indexOfFirst { it === s }.takeIf { it >= 0 }?.let(::removeIndex)
            }
            is Edit.Replaced -> resetStrokes(e.after)
            is Edit.Objects -> Unit
        }
        applyObjects(e.objs, forward = true)
        if (e is Edit.Replaced) fixSelection(e.changed) else fixSelection()
        undoStack.addLast(e)
        version++
        return true
    }

    /**
     * Puts a [takeSelection] back: by undoing it when [take] (its [undoTop]) is still the top step and the undo runs,
     * otherwise (other edits since, or a gesture open) as a paste of [clip] with its top-left corner at ([left], [top]).
     */
    fun restoreTake(take: Any?, clip: InkClip, left: Float, top: Float) {
        if (take != null && undoTop === take && undo()) return
        val b = clip.bounds() ?: return
        paste(clip, left + (b.left + b.right) / 2, top + (b.top + b.bottom) / 2)
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
    fun paintedArea(s: InkStroke): InkRect? = painted(s, bounds(s))

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

    companion object {
        private const val DUPLICATE_OFFSET = 24f

        private fun painted(s: InkStroke, b: InkRect?): InkRect? {
            b ?: return null
            val pad = s.brush.size / 2 + 2f
            return InkRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        }

        /**
         * Builds what [adoptLoaded] needs for [strokes] — each stroke's bounds (taken from [bounds] when given), the
         * grid of their painted areas and the union of those — on any thread, so a big page's ink reaches the canvas
         * without that work on the main thread.
         */
        fun prepare(strokes: List<InkStroke>, bounds: Map<InkStroke, InkRect?>? = null): LoadedInk {
            val b = IdentityHashMap<InkStroke, InkRect?>(strokes.size * 2)
            val grid = SpatialGrid<InkStroke>()
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var btm = Float.NEGATIVE_INFINITY
            for (s in strokes) {
                val sb = if (bounds != null && bounds.containsKey(s)) bounds[s] else s.bounds()
                b[s] = sb
                val p = painted(s, sb) ?: continue
                grid.insert(s, p)
                l = minOf(l, p.left); t = minOf(t, p.top); r = maxOf(r, p.right); btm = maxOf(btm, p.bottom)
            }
            return LoadedInk(ArrayList(strokes), b, grid, if (l <= r) InkRect(l, t, r, btm) else null)
        }
    }

    private fun push(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        version++
    }
}
