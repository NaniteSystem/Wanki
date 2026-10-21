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
}
