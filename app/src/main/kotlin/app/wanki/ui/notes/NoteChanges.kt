package app.wanki.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Change counter of a note being edited: [total] only ever goes up, so "is there anything to
 * save" is a plain comparison with the total of the last save.
 * Total = [edits] (title, text, layout) + every ink layer's version + recognition updates.
 */
class NoteChanges {
    /** Title/text/layout edits; observed by the editor to request a save. */
    var edits by mutableIntStateOf(0)
        private set

    /** Recognized texts stored in blocks; not observed (it must not restart recognition). */
    private var recognitions = 0
    private var saved = 0

    fun edit() { edits++ }

    /** A block is about to be removed: its layer's versions leave the sum, so add them here (+1). */
    fun removed(block: BlockState) { edits += (block.layer?.version ?: 0) + 1 }

    fun recognized() { recognitions++ }

    fun total(blocks: List<BlockState>) = edits + recognitions + blocks.sumOf { it.layer?.version ?: 0 }

    /** The note as loaded counts as saved. */
    fun loaded(blocks: List<BlockState>) { saved = total(blocks) }

    /** The total to save with, or null when everything is saved. Take it before saving. */
    fun unsaved(blocks: List<BlockState>): Int? = total(blocks).takeIf { it != saved }

    /** A save taken at [total] finished; later changes stay unsaved. */
    fun markSaved(total: Int) { if (total > saved) saved = total }
}
