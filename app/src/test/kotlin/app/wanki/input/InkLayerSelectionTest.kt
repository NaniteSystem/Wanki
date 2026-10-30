package app.wanki.input

import androidx.compose.ui.geometry.Offset
import app.wanki.ink.BrushKind
import app.wanki.ink.InkAffine
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkLayerSelectionTest {
    private fun stroke(x: Float, y: Float = 0f, brush: InkBrush = InkBrush()) =
        InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 10f, y + 20f, 10)), brush = brush)

    private fun box(l: Float, t: Float, r: Float, b: Float) = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))

    private fun InkLayer.selectedBounds() = selectionBounds()!!

    @Test
    fun transformIsOneUndoStep() {
        val a = stroke(0f)
        val b = stroke(100f)
        val layer = InkLayer(listOf(a, b))
        layer.selectInside(box(90f, -5f, 120f, 30f))
        assertEquals(listOf(b), layer.selection.toList())
        val v = layer.version

        // scale, then (in the same drag) a turn: the strokes stay as they are until the drag ends
        layer.beginTransform()
        layer.transformSelection(InkAffine.scaleAbout(105f, 10f, 2f))
        layer.transformSelection(InkAffine.rotateAbout(105f, 10f, (PI / 2).toFloat()))
        assertSame(b, layer.strokes[1])
        assertEquals(v, layer.version)
        layer.endTransform()
        assertEquals(v + 1, layer.version)
        val turned = layer.strokes[1]
        assertNotSame(b, turned)
        // the last call counts, not their product: a turn about the centre, width unchanged
        assertEquals(4f, turned.brush.size, 1e-4f)
        assertEquals(115f, turned.points[0].x, 1e-3f)
        assertSame(turned, layer.selection.single())

        // a second drag scales
        layer.beginTransform()
        layer.transformSelection(InkAffine.scaleAbout(105f, 10f, 2f))
        layer.endTransform()
        assertEquals(8f, layer.strokes[1].brush.size, 1e-4f)

        layer.undo()
        assertSame(turned, layer.strokes[1])
        layer.undo()
        assertSame(a, layer.strokes[0])
        assertSame(b, layer.strokes[1])
        // the selection follows the strokes back
        assertSame(b, layer.selection.single())
        assertFalse(layer.canUndo)
    }

    @Test
    fun cancelTransformRestores() {
        val a = stroke(0f)
        val layer = InkLayer(listOf(a))
        layer.selection.add(a)
        val v = layer.version
        layer.beginTransform()
        layer.transformSelection(InkAffine.translate(50f, 0f))
        assertEquals(InkAffine.translate(50f, 0f), layer.liveTransform)
        layer.cancelTransform()
        assertSame(a, layer.strokes.single())
        assertSame(a, layer.selection.single())
        assertEquals(null, layer.liveTransform)
        assertEquals(v, layer.version)
        assertFalse(layer.canUndo)
        // the old move calls are a translate transform
        layer.beginMove()
        layer.moveSelectionBy(5f, 0f)
        layer.moveSelectionBy(5f, 1f)
        layer.endMove()
        assertEquals(10f, layer.strokes.single().points[0].x)
        assertEquals(1f, layer.strokes.single().points[0].y)
    }

    @Test
    fun recolorKeepsHighlighterAlpha() {
        val pen = stroke(0f)
        val hl = stroke(50f, brush = InkBrush(BrushKind.HIGHLIGHTER, 0x66FFEE00, 20f))
        val layer = InkLayer(listOf(pen, hl))
        layer.selection.addAll(listOf(pen, hl))
        layer.recolorSelection(0xFFD23C3C.toInt())
        assertEquals(0xFFD23C3C.toInt(), layer.strokes[0].brush.color)
        assertEquals(0x66D23C3C, layer.strokes[1].brush.color)
        assertEquals(20f, layer.strokes[1].brush.size)
        assertEquals(2, layer.selection.size)
        layer.undo()
        assertSame(pen, layer.strokes[0])
        assertSame(hl, layer.strokes[1])
    }

    @Test
    fun copyPasteCentres() {
        val a = stroke(0f)
        val b = stroke(100f, 40f)
        val layer = InkLayer(listOf(a, b))
        layer.selection.addAll(listOf(a, b))
        val clip = layer.copySelection()
        assertEquals(2, clip.strokes.size)
        assertEquals(0f, clip.bounds()!!.left)
        assertEquals(0f, clip.bounds()!!.top)

        layer.paste(clip, 500f, 700f)
        assertEquals(4, layer.strokes.size)
        assertSame(a, layer.strokes[0])
        assertSame(b, layer.strokes[1])
        val pasted = layer.selectedBounds()
        assertEquals(500f, (pasted.left + pasted.right) / 2, 0.01f)
        assertEquals(700f, (pasted.top + pasted.bottom) / 2, 0.01f)
        assertTrue(layer.selection.none { it === a || it === b })
        layer.undo()
        assertEquals(listOf(a, b), layer.strokes.toList())
        assertTrue(layer.selection.isEmpty())
    }

    @Test
    fun duplicateOffsets() {
        val a = stroke(0f)
        val layer = InkLayer(listOf(a))
        layer.selection.add(a)
        layer.duplicateSelection()
        assertEquals(2, layer.strokes.size)
        val copy = layer.strokes[1]
        assertEquals(24f, copy.points[0].x)
        assertEquals(24f, copy.points[0].y)
        assertSame(copy, layer.selection.single())
        layer.undo()
        assertEquals(listOf(a), layer.strokes.toList())
    }

    @Test
    fun takeSelectionIsUndoable() {
        val a = stroke(0f)
        val b = stroke(100f)
        val layer = InkLayer(listOf(a, b))
        layer.selection.add(b)
        val clip = layer.takeSelection()
        val take = layer.undoTop
        assertEquals(1, clip.strokes.size)
        assertEquals(listOf(a), layer.strokes.toList())
        assertTrue(layer.selection.isEmpty())
        layer.add(stroke(300f))
        assertNotSame(take, layer.undoTop)
        layer.undo()
        assertSame(take, layer.undoTop)
        layer.undo()
        assertEquals(listOf(a, b), layer.strokes.toList())
    }

    @Test
    fun selectOutsideFindsOffPage() {
        val portrait = InkRect(0f, 0f, 1000f, 1414f)
        val inside = stroke(100f, 100f)
        val off = stroke(1200f, 100f)
        // 40 of its 50 units are on the page: mostly inside
        val edge = InkStroke(listOf(InkPoint(960f, 300f, 0), InkPoint(1010f, 310f, 5)))
        val layer = InkLayer(listOf(inside, off, edge))
        assertEquals(1, layer.selectOutside(portrait))
        assertSame(off, layer.selection.single())
    }

    @Test
    fun indexFollowsTransforms() {
        val a = stroke(0f)
        val b = stroke(1000f)
        val layer = InkLayer(listOf(a, b))
        layer.selectInside(box(990f, -5f, 1020f, 30f))
        layer.beginTransform()
        layer.transformSelection(InkAffine.translate(2000f, 500f))
        layer.endTransform()
        assertTrue(layer.strokesIn(InkRect(990f, -5f, 1020f, 30f)).isEmpty())
        assertSame(layer.strokes[1], layer.strokesIn(InkRect(2990f, 495f, 3020f, 530f)).single())
        layer.undo()
        assertSame(b, layer.strokesIn(InkRect(990f, -5f, 1020f, 30f)).single())
    }

    @Test
    fun selectionNeverHoldsStrokesTheLayerLost() {
        val a = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 0f, 10)))
        val layer = InkLayer(listOf(a))
        layer.selection.add(a)
        // a partial erase replaces the stroke with its pieces
        layer.eraseAlong(50f, -5f, 50f, 5f, 5f, EraserMode.PARTIAL)
        assertTrue(layer.selection.all { s -> layer.strokes.any { it === s } })
        layer.undo()
        assertTrue(layer.selection.all { s -> layer.strokes.any { it === s } })
        layer.selection.clear()
        layer.selection.add(layer.strokes[0])
        layer.replaceAll(emptyList())
        assertTrue(layer.selection.isEmpty())
    }

    @Test
    fun selectInsideUsesLayerOrder() {
        val strokes = (0 until 20).map { stroke(it * 30f) }
        val layer = InkLayer(strokes)
        layer.selectInside(box(-5f, -5f, 1000f, 40f))
        assertEquals(strokes, layer.selection.toList())
    }

    @Test
    fun removeByKeysTakesOnlyAsManyAsGiven() {
        val same = stroke(0f)
        val other = stroke(500f)
        // three identical strokes (one key) and another
        val layer = InkLayer(listOf(same, other, same.copy(), same.copy()))
        val key = layer.strokeKey(same)
        assertEquals(2, layer.removeByKeys(listOf(key, key)))
        assertEquals(2, layer.strokes.size)
        assertSame(same, layer.strokes[0])
        assertSame(other, layer.strokes[1])
        layer.undo()
        assertEquals(4, layer.strokes.size)
    }

    @Test
    fun restoreTakePastesWhenTheUndoDoesNotRun() {
        val a = stroke(0f)
        val b = stroke(100f, 50f)
        val layer = InkLayer(listOf(a, b))
        layer.selection.add(b)
        val clip = layer.takeSelection()
        val take = layer.undoTop
        // an eraser drag is open: undo does nothing and says so
        layer.beginErase()
        assertFalse(layer.undo())
        assertSame(take, layer.undoTop)
        layer.restoreTake(take, clip, 100f, 50f)
        assertEquals(2, layer.strokes.size)
        assertEquals(100f, layer.strokes[1].points[0].x, 1e-3f)
        assertEquals(50f, layer.strokes[1].points[0].y, 1e-3f)
        layer.endErase()
        // with nothing open the take itself is undone
        val layer2 = InkLayer(listOf(a, b))
        layer2.selection.add(b)
        val clip2 = layer2.takeSelection()
        layer2.restoreTake(layer2.undoTop, clip2, 100f, 50f)
        assertSame(b, layer2.strokes[1])
        assertFalse(layer2.canUndo)
        assertTrue(layer2.redo())
    }

    @Test
    fun transformFollowsItsStrokesWhenAnotherPointerEdits() {
        val a = stroke(0f)
        val b = stroke(100f)
        val c = stroke(200f)
        val layer = InkLayer(listOf(a, b, c))
        layer.selection.add(c)
        layer.beginTransform()
        layer.transformSelection(InkAffine.translate(0f, 300f))
        // the pen's eraser end removes a meanwhile: c is now at index 1
        layer.removeAt(listOf(0))
        layer.endTransform()
        assertSame(b, layer.strokes[0])
        assertEquals(300f, layer.strokes[1].points[0].y)
        // the transform's undo step reverts only the transform, not the erase
        layer.undo()
        assertEquals(listOf(b, c), layer.strokes.toList())
        layer.undo()
        assertEquals(listOf(a, b, c), layer.strokes.toList())
    }

    @Test
    fun undoOfAnEditOfTheSameLengthDoesNotMoveTheSelectionToAnotherStroke() {
        val a = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 0f, 10)))
        val b = InkStroke(listOf(InkPoint(0f, 100f, 0), InkPoint(10f, 100f, 10)))
        val c = InkStroke(listOf(InkPoint(0f, 200f, 0), InkPoint(100f, 200f, 10)))
        val layer = InkLayer(listOf(a, b, c))
        // one eraser drag removes a and cuts c in two: [b, c1, c2], the same length as before
        layer.beginErase()
        layer.eraseAlong(5f, -5f, 5f, 5f, 3f, EraserMode.PARTIAL)
        layer.eraseAlong(50f, 195f, 50f, 205f, 3f, EraserMode.PARTIAL)
        layer.endErase()
        assertEquals(3, layer.strokes.size)
        val c1 = layer.strokes[1]
        layer.selection.add(c1)
        layer.undo()
        // c1 is gone; position 1 now holds b, which was never selected
        assertTrue(layer.selection.isEmpty())
        // a recolour undone and redone keeps its stroke selected, by identity
        layer.selection.add(c)
        layer.recolorSelection(0xFFD23C3C.toInt())
        layer.undo()
        assertSame(c, layer.selection.single())
        layer.redo()
        assertSame(layer.strokes[2], layer.selection.single())
    }

    @Test
    fun restoredStrokesSurviveACancelledEraseDrag() {
        val a = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 0f, 10)))
        val b = stroke(300f, 300f)
        val layer = InkLayer(listOf(a, b))
        layer.selection.add(b)
        val clip = layer.takeSelection()
        val take = layer.undoTop
        // a finger eraser drag is open (and has cut a) when «Отменить» or a failed move puts the strokes back
        layer.beginErase()
        layer.eraseAlong(50f, -5f, 50f, 5f, 5f, EraserMode.PARTIAL)
        layer.restoreTake(take, clip, 300f, 300f)
        // the drag turns out to be a pinch: its erase is dropped, the restored strokes stay
        layer.cancelErase()
        assertEquals(2, layer.strokes.size)
        assertSame(a, layer.strokes[0])
        assertEquals(300f, layer.strokes[1].points[0].x, 1e-3f)
        assertEquals(300f, layer.strokes[1].points[0].y, 1e-3f)
        // an erase drag that ends keeps them too, and its undo step does not remove them
        val layer2 = InkLayer(listOf(a))
        layer2.beginErase()
        layer2.eraseAlong(50f, -5f, 50f, 5f, 5f, EraserMode.PARTIAL)
        layer2.paste(clip, 500f, 500f)
        assertTrue(layer2.endErase())
        layer2.undo() // the erase
        assertEquals(2, layer2.strokes.size)
        layer2.undo() // the paste
        assertEquals(listOf(a), layer2.strokes.toList())
    }
}
