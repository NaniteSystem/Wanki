package app.wanki.ui.notes

import app.wanki.data.NoteBlockType
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoteChangesTest {
    private val stroke = InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(0f, 10f, 10)))

    /** What NoteEditorScreen.save() does: store only when something changed since the last save. */
    private fun save(changes: NoteChanges, blocks: List<BlockState>): Boolean {
        val v = changes.unsaved(blocks) ?: return false
        changes.markSaved(v)
        return true
    }

    @Test
    fun removingABlockAfterOneStrokeNeedsASave() {
        val changes = NoteChanges()
        val blocks = mutableListOf(BlockState("t", NoteBlockType.TEXT))
        changes.loaded(blocks)
        assertNull(changes.unsaved(blocks), "nothing to save right after opening")

        val ink = BlockState("i", NoteBlockType.INK)
        blocks += ink
        changes.edit()
        ink.layer!!.add(stroke)
        assertEquals(true, save(changes, blocks))

        // «Убрать»: the sum of layer versions drops by exactly what the edit counter used to gain
        changes.removed(ink)
        blocks.remove(ink)
        assertNotNull(changes.unsaved(blocks), "the removal must be saved")
        assertEquals(true, save(changes, blocks))
        assertNull(changes.unsaved(blocks))
    }

    @Test
    fun recognitionDuringASaveIsSavedByTheNextRun() {
        val changes = NoteChanges()
        val blocks = listOf(BlockState("i", NoteBlockType.INK))
        changes.loaded(blocks)
        blocks[0].layer!!.add(stroke)

        // a save starts and takes its snapshot…
        val running = changes.unsaved(blocks)!!
        // …recognition stores new text in the block meanwhile…
        changes.recognized()
        // …and the save finishes with the snapshot it took
        changes.markSaved(running)
        assertNotNull(changes.unsaved(blocks), "the queued run must store the recognized text")
    }

    @Test
    fun totalOnlyGoesUp() {
        val changes = NoteChanges()
        val a = BlockState("a", NoteBlockType.INK)
        val b = BlockState("b", NoteBlockType.INK)
        val blocks = mutableListOf(a, b)
        val seen = mutableListOf(changes.total(blocks))
        a.layer!!.add(stroke); seen += changes.total(blocks)
        b.layer!!.add(stroke); seen += changes.total(blocks)
        b.layer.add(stroke); seen += changes.total(blocks)
        changes.removed(b); blocks.remove(b); seen += changes.total(blocks)
        changes.edit(); seen += changes.total(blocks)
        changes.recognized(); seen += changes.total(blocks)
        assertEquals(seen.sorted().distinct(), seen, "strictly increasing: $seen")
    }
}
