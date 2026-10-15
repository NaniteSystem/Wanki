package app.wanki.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.ink.strokes.Stroke
import app.wanki.ink.InkDrawing
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * The strokes of one canvas (world coordinates) with undo/redo. Renderable androidx.ink strokes
 * and bounds are cached per stroke. [version] changes on every edit (for autosave/recognition).
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

    val isEmpty: Boolean get() = strokes.isEmpty()
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
        push(Edit.Removed(hit.map { it.index to it.value }))
        return true
    }

    fun replaceAll(newStrokes: List<InkStroke>, undoable: Boolean = true) {
        val before = strokes.toList()
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

    fun undo() {
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
