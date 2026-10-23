package app.wanki.input

import app.wanki.ink.InkCodec
import app.wanki.ink.InkPoint
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkLayerIndexTest {
    private fun stroke(x: Float, y: Float = 0f) = InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x, y + 10f, 10)))

    private val everything = InkRect(-1000f, -1000f, 5000f, 5000f)
    private val emptyCorner = InkRect(4000f, 4000f, 4100f, 4100f)

    private fun assertIndexMatches(layer: InkLayer) {
        val expected = layer.strokes.toList()
        val got = layer.strokesIn(everything)
        assertEquals(expected.size, got.size)
        expected.zip(got).forEach { (a, b) -> assertSame(a, b) }
        assertTrue(layer.strokesIn(emptyCorner).isEmpty())
    }

    @Test
    fun indexFollowsEveryEdit() {
        val layer = InkLayer(listOf(stroke(0f), stroke(100f), stroke(2000f)))
        assertIndexMatches(layer)

        layer.add(stroke(300f))
        assertIndexMatches(layer)

        layer.removeAt(listOf(1))
        assertIndexMatches(layer)

        layer.beginErase()
        layer.eraseAt(0f, 5f, 5f)
        assertIndexMatches(layer)
        layer.endErase()
        assertIndexMatches(layer)

        layer.selectInside(listOf(androidx.compose.ui.geometry.Offset(1900f, -50f), androidx.compose.ui.geometry.Offset(2100f, -50f), androidx.compose.ui.geometry.Offset(2100f, 100f), androidx.compose.ui.geometry.Offset(1900f, 100f)))
        assertTrue(layer.hasSelection)
        layer.beginMove()
        layer.moveSelectionBy(50f, 50f)
        assertIndexMatches(layer)
        assertEquals(1, layer.strokesIn(InkRect(2040f, 40f, 2060f, 60f)).size)
        layer.endMove()
        assertIndexMatches(layer)

        while (layer.canUndo) { layer.undo(); assertIndexMatches(layer) }
        while (layer.canRedo) { layer.redo(); assertIndexMatches(layer) }
    }

    @Test
    fun strokesInReturnsLayerOrder() {
        val a = stroke(0f); val b = stroke(10f); val c = stroke(20f)
        val layer = InkLayer(listOf(a, b, c))
        assertEquals(listOf(a, b, c), layer.strokesIn(InkRect(-5f, -5f, 30f, 30f)))
        assertEquals(listOf(a), layer.strokesIn(InkRect(-5f, -5f, 1f, 1f)).filter { it === a })
    }

    @Test
    fun strokesRevBumpsOnEveryChange() {
        val layer = InkLayer()
        val r0 = layer.strokesRev
        layer.add(stroke(0f))
        assertTrue(layer.strokesRev > r0)
        val r1 = layer.strokesRev
        layer.undo()
        assertTrue(layer.strokesRev > r1)
    }

    @Test
    fun chunkCachedByIdentity() {
        val s = stroke(0f)
        val layer = InkLayer(listOf(s))
        assertSame(layer.chunk(s), layer.chunk(s))
        val moved = s.copy(points = s.points.map { it.copy(x = it.x + 5f) })
        assertNotSame(layer.chunk(s), layer.chunk(moved))
        assertEquals(InkCodec.strokeKey(layer.chunk(s)), layer.strokeKey(s))
    }

    @Test
    fun encodeSnapshotEqualsCodec() {
        val layer = InkLayer(listOf(stroke(0f), stroke(100f)))
        assertContentEquals(InkCodec.encode(layer.drawing()), layer.encodeSnapshot())
        layer.clear()
        assertNull(layer.encodeSnapshot())
    }
}
