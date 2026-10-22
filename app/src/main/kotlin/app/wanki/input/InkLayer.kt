package app.wanki.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.ink.strokes.Stroke
import app.wanki.ink.InkDrawing
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * The strokes of one canvas (world coordinates) with undo/redo. Renderable androidx.ink strokes
 * and bounds are cached per stroke. [version] changes once per finished edit — one stroke, one
 * eraser drag, one lasso move, or a cancelled drag that put strokes back — so autosave and
 * recognition run once per gesture.
 */
class InkLayer(initial: List<InkStroke> = emptyList()) {
    val strokes = mutableStateListOf<InkStroke>().apply { addAll(initial) }

    var version by mutableIntStateOf(0)
        private set

    private val render = IdentityHashMap<InkStroke, Stroke?>()
    private val boundsCache = IdentityHashMap<InkStroke, InkRect?>()
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** Uptime of the first stroke since the last [clear]; stroke times are relative to it. */
    internal var timeBaseUptime = -1L

    private sealed interface Edit {
        data class Added(val strokes: List<InkStroke>) : Edit
        data class Removed(val items: List<Pair<Int, InkStroke>>) : Edit
        data class Replaced(val before: List<InkStroke>, val after: List<InkStroke>) : Edit
    }

    /** Lasso being drawn (world coordinates), and the strokes it selected. */
    var lasso by mutableStateOf<List<Offset>?>(null)
    val selection = mutableStateListOf<InkStroke>()

    val isEmpty: Boolean get() = strokes.isEmpty()
    val hasSelection: Boolean get() = selection.isNotEmpty()

    /** Selects strokes with most of their points inside the polygon. */
    fun selectInside(polygon: List<Offset>) {
        selection.clear()
        if (polygon.size < 3) return
        selection.addAll(strokes.filter { s -> s.points.isNotEmpty() && s.points.count { inside(polygon, it.x, it.y) } >= s.points.size * 0.6 })
    }

    fun clearSelection() = selection.clear()

    fun selectionBounds(): InkRect? = selection.mapNotNull { bounds(it) }.reduceOrNull { a, b -> a.union(b) }

    /** Removes strokes by index (undoable), e.g. after a scratch-out gesture. */
    fun removeAt(indices: List<Int>) {
        val hit = indices.sorted().filter { it in strokes.indices }.map { it to strokes[it] }
        if (hit.isEmpty()) return
        hit.asReversed().forEach { strokes.removeAt(it.first) }
        push(Edit.Removed(hit))
    }

    private var moveBefore: List<InkStroke>? = null
    private var moveIndices: List<Int> = emptyList()

    /** Starts dragging the selection; [moveSelectionBy] then shifts it live, [endMove] makes it one undo step. */
    fun beginMove() {
        moveBefore = strokes.toList()
        moveIndices = strokes.indices.filter { i -> selection.any { it === strokes[i] } }
    }

    fun moveSelectionBy(dx: Float, dy: Float) {
        if (moveBefore == null || (dx == 0f && dy == 0f)) return
        val moved = moveIndices.map { i ->
            val s = strokes[i]
            s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }).also { strokes[i] = it }
        }
        selection.clear()
        selection.addAll(moved)
    }

    fun endMove() {
        val before = moveBefore ?: return
        moveBefore = null
        if (before != strokes.toList()) push(Edit.Replaced(before, strokes.toList()))
    }

    /** Drops the selection drag and puts the strokes back (e.g. it turned out to be a pinch or tap). */
    fun cancelMove() {
        val before = moveBefore ?: return
        moveBefore = null
        val moved = before != strokes.toList()
        strokes.clear()
        strokes.addAll(before)
        selection.clear()
        selection.addAll(moveIndices.mapNotNull { before.getOrNull(it) })
        // a save during the drag may have stored the moved strokes: the restored ones must be saved again
        if (moved) version++
    }

    /** Removes the selected strokes (undoable). */
    fun deleteSelection() {
        val hit = strokes.withIndex().filter { (_, s) -> selection.any { it === s } }
        if (hit.isEmpty()) return
        hit.asReversed().forEach { strokes.removeAt(it.index) }
        push(Edit.Removed(hit.map { it.index to it.value }))
        selection.clear()
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

    fun renderable(s: InkStroke): Stroke? = render.getOrPut(s) { StrokeConversion.toInk(s) }
    fun bounds(s: InkStroke): InkRect? = boundsCache.getOrPut(s) { s.bounds() }

    fun add(s: InkStroke) {
        strokes += s
        push(Edit.Added(listOf(s)))
    }

    fun eraseAt(x: Float, y: Float, radius: Float): Boolean {
        val hit = strokes.withIndex().filter { (_, s) ->
            val b = bounds(s) ?: return@filter false
            x >= b.left - radius && x <= b.right + radius && y >= b.top - radius && y <= b.bottom + radius &&
                s.points.any { hypot(it.x - x, it.y - y) <= radius + s.brush.size / 2 }
        }
        if (hit.isEmpty()) return false
        hit.asReversed().forEach { strokes.removeAt(it.index) }
        if (eraseBefore != null) eraseChanged = true else push(Edit.Removed(hit.map { it.index to it.value }))
        return true
    }

    private var eraseBefore: List<InkStroke>? = null
    private var eraseChanged = false

    /**
     * Starts an eraser drag: [eraseAt] then removes strokes live without undo steps or [version]
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
            strokes.clear()
            strokes.addAll(before)
            // a save during the drag may have stored the half-erased strokes: save the restored ones again
            version++
        }
    }

    fun replaceAll(newStrokes: List<InkStroke>, undoable: Boolean = true) {
        val before = strokes.toList()
        eraseBefore = null // a reload or replace ends any eraser drag or move in progress
        moveBefore = null
        strokes.clear()
        strokes.addAll(newStrokes)
        if (undoable) push(Edit.Replaced(before, newStrokes)) else {
            undoStack.clear(); redoStack.clear(); version++
        }
    }

    fun clear() {
        if (strokes.isNotEmpty()) replaceAll(emptyList())
        timeBaseUptime = -1L
    }

    /** An eraser drag or selection move holds a snapshot; undo/redo now would make it stale. */
    private val gestureOpen: Boolean get() = eraseBefore != null || moveBefore != null

    fun undo() {
        if (gestureOpen) return
        val e = undoStack.removeLastOrNull() ?: return
        when (e) {
            is Edit.Added -> e.strokes.forEach { s -> strokes.removeAt(strokes.indexOfLast { it === s }) }
            is Edit.Removed -> e.items.sortedBy { it.first }.forEach { (i, s) -> strokes.add(i.coerceAtMost(strokes.size), s) }
            is Edit.Replaced -> { strokes.clear(); strokes.addAll(e.before) }
        }
        redoStack.addLast(e)
        version++
    }

    fun redo() {
        if (gestureOpen) return
        val e = redoStack.removeLastOrNull() ?: return
        when (e) {
            is Edit.Added -> strokes.addAll(e.strokes)
            is Edit.Removed -> e.items.sortedByDescending { it.first }.forEach { (_, s) ->
                strokes.indexOfFirst { it === s }.takeIf { it >= 0 }?.let(strokes::removeAt)
            }
            is Edit.Replaced -> { strokes.clear(); strokes.addAll(e.after) }
        }
        undoStack.addLast(e)
        version++
    }

    private fun push(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        version++
    }
}
