package app.wanki.input

import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkLayerEraseTest {
    private fun stroke(x: Float) = InkStroke(listOf(InkPoint(x, 0f, 0), InkPoint(x, 10f, 10)))

    private val a = stroke(0f)
    private val b = stroke(100f)
    private val c = stroke(200f)

    @Test
    fun oneEraserDragIsOneUndoStep() {
        val layer = InkLayer(listOf(a, b, c))
        val v0 = layer.version
        layer.beginErase()
        assertTrue(layer.eraseAt(0f, 5f, 5f))
        assertTrue(layer.eraseAt(100f, 5f, 5f))
        assertEquals(v0, layer.version, "no version change in the middle of a drag")
        assertTrue(layer.endErase())
        assertEquals(listOf(c), layer.strokes.toList())
        assertEquals(v0 + 1, layer.version, "one version change per drag")
        layer.undo()
        assertEquals(listOf(a, b, c), layer.strokes.toList())
        assertFalse(layer.canUndo)
        layer.redo()
        assertEquals(listOf(c), layer.strokes.toList())
    }

    @Test
    fun eraseGestureThatHitsNothingAddsNoStep() {
        val layer = InkLayer(listOf(a, b, c))
        val v0 = layer.version
        layer.beginErase()
        assertFalse(layer.eraseAt(50f, 500f, 5f))
        assertFalse(layer.endErase())
        assertEquals(v0, layer.version)
        assertFalse(layer.canUndo)
        assertEquals(listOf(a, b, c), layer.strokes.toList())
    }

    @Test
    fun cancelledEraseRestoresStrokesWithoutStep() {
        val layer = InkLayer(listOf(a, b, c))
        layer.beginErase()
        layer.eraseAt(100f, 5f, 5f)
        layer.cancelErase()
        assertEquals(listOf(a, b, c), layer.strokes.toList())
        assertFalse(layer.canUndo)
    }

    @Test
    fun eraseOutsideGestureStillUndoable() {
        val layer = InkLayer(listOf(a, b, c))
        assertTrue(layer.eraseAt(0f, 5f, 5f))
        layer.undo()
        assertEquals(listOf(a, b, c), layer.strokes.toList())
    }

    @Test
    fun undoIsIgnoredWhileEraseOpen() {
        val layer = InkLayer(listOf(a))
        layer.add(b)
        layer.beginErase()
        layer.eraseAt(0f, 5f, 5f)
        val v = layer.version
        layer.undo()
        layer.redo()
        assertEquals(listOf(b), layer.strokes.toList())
        assertEquals(v, layer.version)
        assertTrue(layer.endErase())
        layer.undo()
        assertEquals(listOf(a, b), layer.strokes.toList())
        layer.undo()
        assertEquals(listOf(a), layer.strokes.toList())
    }

    @Test
    fun undoIsIgnoredWhileMoveOpen() {
        val layer = InkLayer(listOf(a))
        layer.add(b)
        layer.selection.add(b)
        layer.beginMove()
        layer.moveSelectionBy(5f, 0f)
        val v = layer.version
        layer.undo()
        assertEquals(2, layer.strokes.size)
        assertEquals(105f, layer.strokes[1].points[0].x)
        assertEquals(v, layer.version)
        layer.endMove()
        layer.undo()
        assertEquals(listOf(a, b), layer.strokes.toList())
    }

    @Test
    fun cancelledMoveRestoresStrokesWithoutStep() {
        val layer = InkLayer(listOf(a, b))
        layer.selection.add(b)
        val v = layer.version
        layer.beginMove()
        layer.moveSelectionBy(5f, 0f)
        layer.cancelMove()
        assertEquals(listOf(a, b), layer.strokes.toList())
        // a save during the drag may have stored the moved strokes: the restored ones are a new version
        assertEquals(v + 1, layer.version)
        assertFalse(layer.canUndo)
        layer.endMove() // no open move any more: nothing pushed
        assertFalse(layer.canUndo)
    }

    @Test
    fun cancelledEraseThatChangedStrokesIsANewVersion() {
        // a save may have stored the half-erased strokes: putting them back must be saved again
        val layer = InkLayer(listOf(a, b, c))
        val v0 = layer.version
        layer.beginErase()
        assertTrue(layer.eraseAt(0f, 5f, 5f))
        layer.cancelErase()
        assertEquals(listOf(a, b, c), layer.strokes.toList())
        assertTrue(layer.version > v0)
        assertFalse(layer.canUndo, "a cancelled drag is not an undo step")
    }

    @Test
    fun cancelledEraseThatChangedNothingKeepsTheVersion() {
        val layer = InkLayer(listOf(a, b, c))
        val v0 = layer.version
        layer.beginErase()
        layer.cancelErase()
        assertEquals(v0, layer.version)
    }

    /** Three horizontal strokes one above the other, each 0..100 long. */
    private fun rows() = listOf(0f, 20f, 40f).map { y -> InkStroke(listOf(InkPoint(0f, y, 0), InkPoint(100f, y, 100))) }

    @Test
    fun partialDragIsOneUndoStep() {
        val originals = rows()
        val layer = InkLayer(originals)
        val v0 = layer.version
        layer.beginErase()
        // down the middle across all three, in two steps
        assertTrue(layer.eraseAlong(50f, -10f, 50f, 25f, 3f, EraserMode.PARTIAL))
        assertTrue(layer.eraseAlong(50f, 25f, 50f, 50f, 3f, EraserMode.PARTIAL))
        assertEquals(v0, layer.version, "no version change in the middle of a drag")
        assertTrue(layer.endErase())
        assertEquals(6, layer.strokes.size)
        assertEquals(v0 + 1, layer.version)
        val pieces = layer.strokes.toList()
        // pieces stay where the strokes were in the stacking order
        assertEquals(listOf(0f, 0f, 20f, 20f, 40f, 40f), pieces.map { it.points[0].y })
        layer.undo()
        assertEquals(3, layer.strokes.size)
        originals.forEachIndexed { i, s -> assertTrue(s === layer.strokes[i], "original $i is back as the same instance") }
        assertFalse(layer.canUndo)
        layer.redo()
        assertEquals(pieces, layer.strokes.toList())
        pieces.forEachIndexed { i, s -> assertTrue(s === layer.strokes[i]) }
    }

    @Test
    fun partialEraseOutsideADragIsOneUndoStep() {
        val originals = rows()
        val layer = InkLayer(originals)
        assertTrue(layer.eraseAlong(50f, 0f, 50f, 0f, 3f, EraserMode.PARTIAL))
        assertEquals(4, layer.strokes.size)
        layer.undo()
        assertEquals(originals, layer.strokes.toList())
        assertFalse(layer.canUndo)
    }

    @Test
    fun partialEraseKeepsGridInStep() {
        val layer = InkLayer(rows())
        layer.beginErase()
        layer.eraseAlong(50f, 0f, 50f, 0f, 3f, EraserMode.PARTIAL)
        layer.endErase()
        // the grid answers with the pieces, not the cut strokes
        val found = layer.strokesIn(app.wanki.ink.InkRect(-5f, -5f, 105f, 45f))
        assertEquals(layer.strokes.toList(), found)
        assertEquals(4, found.size)
    }

    @Test
    fun wholeModeRemovesTouched() {
        val originals = rows()
        val layer = InkLayer(originals)
        layer.beginErase()
        assertTrue(layer.eraseAlong(50f, 15f, 50f, 25f, 3f, EraserMode.WHOLE))
        assertTrue(layer.endErase())
        assertEquals(listOf(originals[0], originals[2]), layer.strokes.toList())
        layer.undo()
        assertEquals(originals, layer.strokes.toList())
    }

    @Test
    fun partialMissChangesNothing() {
        val layer = InkLayer(rows())
        layer.beginErase()
        assertFalse(layer.eraseAlong(50f, 100f, 60f, 100f, 3f, EraserMode.PARTIAL))
        assertFalse(layer.endErase())
    }

    @Test
    fun cancelledPartialEraseRestoresTheOriginals() {
        val originals = rows()
        val layer = InkLayer(originals)
        layer.beginErase()
        layer.eraseAlong(50f, 0f, 50f, 40f, 3f, EraserMode.PARTIAL)
        layer.cancelErase()
        assertEquals(3, layer.strokes.size)
        originals.forEachIndexed { i, s -> assertTrue(s === layer.strokes[i]) }
        assertFalse(layer.canUndo)
    }
}
