package app.wanki.input

import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The layer tells the ink tiles where every change happened, so no tile keeps stale ink. */
class InkLayerChangesTest {
    private fun stroke(x: Float, y: Float = 0f, size: Float = 4f) =
        InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 10f, y + 10f, 10)), brush = InkBrush(size = size))

    private class Recorder : InkLayer.ChangeListener {
        val changed = ArrayList<InkRect>()
        val appended = ArrayList<InkStroke>()
        override fun changed(area: InkRect) { changed += area }
        override fun appended(stroke: InkStroke, area: InkRect) { appended += stroke }
        fun clear() { changed.clear(); appended.clear() }
    }

    private fun InkRect.contains(o: InkRect) = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom

    @Test
    fun everyEditReportsItsArea() {
        val a = stroke(0f)
        val b = stroke(500f, size = 20f)
        val layer = InkLayer(listOf(a))
        val rec = Recorder()
        layer.addChangeListener(rec)

        // a new stroke on top is an append, with the brush included in its area
        layer.add(b)
        assertEquals(listOf(b), rec.appended)
        assertTrue(rec.changed.isEmpty())
        assertEquals(InkRect(488f, -12f, 522f, 22f), layer.paintedArea(b))

        // undo removes it: a change over its painted area
        rec.clear()
        layer.undo()
        assertEquals(1, rec.changed.size)
        assertTrue(rec.changed[0].contains(layer.paintedArea(b)!!))
        // redo puts it back on top
        rec.clear()
        layer.redo()
        assertEquals(listOf(b), rec.appended)

        // an eraser drag, then its cancel: both report the erased stroke
        rec.clear()
        layer.beginErase()
        assertTrue(layer.eraseAt(10f, 10f, 3f))
        assertEquals(1, rec.changed.size)
        layer.cancelErase()
        assertEquals(2, rec.changed.size)
        assertTrue(rec.changed.all { it.contains(InkRect(0f, 0f, 10f, 10f)) })

        // a move reports the old and the new place
        rec.clear()
        layer.selectInside(listOf(androidx.compose.ui.geometry.Offset(-5f, -5f), androidx.compose.ui.geometry.Offset(20f, -5f), androidx.compose.ui.geometry.Offset(20f, 20f), androidx.compose.ui.geometry.Offset(-5f, 20f)))
        layer.beginMove()
        layer.moveSelectionBy(100f, 0f)
        layer.endMove()
        assertTrue(rec.changed.any { it.contains(InkRect(0f, 0f, 10f, 10f)) })
        assertTrue(rec.changed.any { it.contains(InkRect(100f, 0f, 110f, 10f)) })

        // undo of the move (a whole-list reset) reports both places again, and nothing for the untouched stroke
        rec.clear()
        layer.undo()
        assertTrue(rec.changed.any { it.contains(InkRect(0f, 0f, 10f, 10f)) })
        assertTrue(rec.changed.any { it.contains(InkRect(100f, 0f, 110f, 10f)) })
        assertTrue(rec.changed.none { it.intersects(InkRect(500f, 0f, 510f, 10f)) })

        layer.removeChangeListener(rec)
        rec.clear()
        layer.clear()
        assertTrue(rec.changed.isEmpty())
    }

    @Test
    fun reorderReportsTheStrokesThatMoved() {
        val a = stroke(0f)
        val b = stroke(300f)
        val c = stroke(600f)
        val layer = InkLayer(listOf(a, b, c))
        val rec = Recorder()
        layer.addChangeListener(rec)
        layer.replaceAll(listOf(a, c, b))
        // b and c swapped places in the stacking order; a stayed
        assertTrue(rec.changed.any { it.contains(InkRect(300f, 0f, 310f, 10f)) })
        assertTrue(rec.changed.any { it.contains(InkRect(600f, 0f, 610f, 10f)) })
        assertTrue(rec.changed.none { it.intersects(InkRect(0f, 0f, 10f, 10f)) })
    }

    @Test
    fun strokesInFindsAThickStrokeJustOutside() {
        // its points end 6 units left of the rectangle, but its 20-unit brush paints into it
        val thick = stroke(-16f, size = 20f)
        val layer = InkLayer(listOf(stroke(1000f), thick))
        assertSame(thick, layer.strokesIn(InkRect(0f, 0f, 256f, 256f)).single())
    }
}
