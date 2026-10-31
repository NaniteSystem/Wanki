package app.wanki.input

import androidx.compose.ui.geometry.Offset
import app.wanki.data.PageObjectKind
import app.wanki.ink.InkAffine
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InkLayerObjectsTest {
    private fun stroke(x: Float, y: Float = 0f) = InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 10f, y + 20f, 10)))

    private fun text(id: String, x: Float, y: Float, w: Float = 100f, h: Float = 40f, z: Int = 0) = PageObject(
        id, PageObjectKind.TEXT, x, y, w, h, 0f, z, "t$id", ToolSelection.DEFAULT_INK, 32f, null,
    )

    private fun box(l: Float, t: Float, r: Float, b: Float) = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))

    @Test
    fun addObjectUndo() {
        val layer = InkLayer()
        val v = layer.version
        layer.addObject(text("a", 0f, 0f))
        assertEquals(listOf("a"), layer.objects.map { it.id })
        assertEquals(v + 1, layer.version)
        assertTrue(layer.canUndo)
        assertTrue(layer.undo())
        assertTrue(layer.objects.isEmpty())
        assertTrue(layer.redo())
        assertEquals(listOf("a"), layer.objects.map { it.id })

        layer.updateObject(layer.objects[0].copy(text = "new"))
        assertEquals("new", layer.objects[0].text)
        layer.undo()
        assertEquals("ta", layer.objects[0].text)

        layer.removeObjects(listOf("a"))
        assertTrue(layer.objects.isEmpty())
        layer.undo()
        assertEquals(listOf("a"), layer.objects.map { it.id })
    }

    @Test
    fun lassoSelectsObjectByCentre() {
        val layer = InkLayer(listOf(stroke(1000f)))
        layer.addObject(text("in", 0f, 0f, w = 100f, h = 40f)) // centre (50, 20)
        layer.addObject(text("out", 200f, 0f, w = 400f, h = 40f)) // centre (400, 20): its left part is inside the loop
        layer.selectInside(box(-10f, -10f, 300f, 60f))
        assertEquals(listOf("in"), layer.selectedObjects.toList())
        assertTrue(layer.selection.isEmpty())
        assertTrue(layer.hasSelection)
        val b = layer.selectionBounds()!!
        assertEquals(0f, b.left)
        assertEquals(100f, b.right)
        layer.clearSelection()
        assertFalse(layer.hasSelection)
    }

    @Test
    fun transformMixedSelectionOneStep() {
        val s = stroke(10f, 10f)
        val layer = InkLayer(listOf(s))
        layer.addObject(text("a", 0f, 0f))
        layer.selectInside(box(-50f, -50f, 200f, 200f))
        assertEquals(1, layer.selection.size)
        assertEquals(listOf("a"), layer.selectedObjects.toList())
        val v = layer.version
        layer.beginTransform()
        layer.transformSelection(InkAffine.translate(100f, 50f))
        // nothing changes until the drag ends
        assertEquals(0f, layer.objects[0].x)
        layer.endTransform()
        assertEquals(v + 1, layer.version)
        assertEquals(100f, layer.objects[0].x)
        assertEquals(110f, layer.strokes[0].points[0].x)
        // still selected, by identity
        assertEquals(listOf("a"), layer.selectedObjects.toList())
        assertEquals(layer.strokes[0], layer.selection.single())

        assertTrue(layer.undo())
        assertEquals(0f, layer.objects[0].x)
        assertEquals(10f, layer.strokes[0].points[0].x)
        assertEquals(listOf("a"), layer.selectedObjects.toList())
        assertTrue(layer.redo())
        assertEquals(100f, layer.objects[0].x)
        assertEquals(110f, layer.strokes[0].points[0].x)

        // only an object selected: a transform still works, and a cancel changes nothing
        layer.clearSelection()
        layer.selectInside(box(90f, 40f, 300f, 200f)) // object centre (150, 70); the stroke's points too
        layer.selection.clear()
        layer.beginTransform()
        layer.transformSelection(InkAffine.translate(1f, 1f))
        layer.cancelTransform()
        assertEquals(100f, layer.objects[0].x)
    }

    @Test
    fun copyPasteObjectsGetNewIds() {
        val layer = InkLayer(listOf(stroke(10f, 10f)))
        layer.addObject(text("a", 0f, 0f, z = 4))
        layer.selectInside(box(-50f, -50f, 200f, 200f))
        val clip = layer.copySelection()
        assertEquals(1, clip.strokes.size)
        assertEquals(1, clip.objects.size)
        // relative to the top-left corner of everything copied
        assertEquals(0f, clip.objects[0].x)
        val v = layer.version
        layer.paste(clip, 500f, 500f)
        assertEquals(v + 1, layer.version)
        assertEquals(2, layer.objects.size)
        val pasted = layer.objects.last()
        assertNotEquals("a", pasted.id)
        assertTrue(pasted.z > 4)
        assertEquals(listOf(pasted.id), layer.selectedObjects.toList())
        assertEquals(2, layer.strokes.size)
        // pasting again gives other ids
        layer.paste(clip, 500f, 500f)
        assertEquals(3, layer.objects.map { it.id }.toSet().size)
        // one undo removes a paste's strokes and objects together
        layer.undo()
        assertEquals(2, layer.objects.size)
        assertEquals(2, layer.strokes.size)

        // duplicate and delete include objects
        layer.clearSelection()
        layer.selectInside(box(-50f, -50f, 200f, 200f))
        layer.duplicateSelection()
        assertEquals(3, layer.objects.size)
        layer.deleteSelection()
        assertEquals(2, layer.objects.size)
        layer.undo()
        assertEquals(3, layer.objects.size)
    }

    @Test
    fun objectsRevChangesOnlyForObjects() {
        val layer = InkLayer()
        val o = layer.objectsRev
        val s = layer.strokesRev
        layer.add(stroke(0f))
        assertEquals(o, layer.objectsRev)
        assertNotEquals(s, layer.strokesRev)
        val s2 = layer.strokesRev
        layer.addObject(text("a", 0f, 0f))
        assertNotEquals(o, layer.objectsRev)
        assertEquals(s2, layer.strokesRev)
        val o2 = layer.objectsRev
        layer.undo() // the object
        assertNotEquals(o2, layer.objectsRev)
        assertEquals(s2, layer.strokesRev)
    }

    @Test
    fun recolourChangesTextBlocksOnly() {
        val layer = InkLayer()
        layer.addObject(text("a", 0f, 0f))
        layer.addObject(text("p", 0f, 100f).copy(kind = PageObjectKind.IMAGE, media = "page-p.jpg"))
        layer.selectInside(box(-50f, -50f, 300f, 300f))
        layer.recolorSelection(0xFFD23C3C.toInt())
        assertEquals(0xFFD23C3C.toInt(), layer.objects.first { it.id == "a" }.color)
        assertEquals(ToolSelection.DEFAULT_INK, layer.objects.first { it.id == "p" }.color)
        layer.undo()
        assertEquals(ToolSelection.DEFAULT_INK, layer.objects.first { it.id == "a" }.color)
    }

    @Test
    fun takeAndRemoveByKeysCarryObjects() {
        val layer = InkLayer(listOf(stroke(10f, 10f)))
        layer.addObject(text("a", 0f, 0f))
        layer.selectInside(box(-50f, -50f, 200f, 200f))
        val clip = layer.takeSelection()
        val take = layer.undoTop
        assertTrue(layer.objects.isEmpty())
        assertTrue(layer.strokes.isEmpty())
        // an open eraser drag blocks the undo: the clip comes back as a paste, objects with new ids
        layer.beginErase()
        layer.restoreTake(take, clip, 0f, 0f)
        layer.cancelErase()
        assertEquals(1, layer.objects.size)
        assertEquals(1, layer.strokes.size)
        assertEquals(0f, layer.objects[0].x)
        val id = layer.objects[0].id
        assertEquals(2, layer.removeByKeys(listOf(layer.strokeKey(layer.strokes[0])), listOf(id)))
        assertTrue(layer.objects.isEmpty() && layer.strokes.isEmpty())
        layer.undo()
        assertEquals(1, layer.objects.size)
        assertEquals(1, layer.strokes.size)
    }
}
