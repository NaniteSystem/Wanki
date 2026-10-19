package app.wanki.ui.texts

import app.wanki.texts.TextKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PieceDraftsTest {
    private fun o(old: Int?, splitFrom: Int? = null) = PieceOrigin(old, splitFrom)

    @Test
    fun unchangedPiecesKeepTheirDrafts() {
        assertEquals(listOf(o(0), o(1), o(2)), PieceDrafts.align(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun editedPieceKeepsItsDraft() {
        assertEquals(listOf(o(0), o(1), o(2)), PieceDrafts.align(listOf("a", "b", "c"), listOf("a", "B!", "c")))
    }

    @Test
    fun splitPieceCopiesTheSchedule() {
        assertEquals(listOf(o(0), o(1), o(null, 1), o(2)), PieceDrafts.align(listOf("a", "b c", "d"), listOf("a", "b", "c", "d")))
    }

    @Test
    fun joinDropsTheSecondPiece() {
        assertEquals(listOf(o(0), o(1), o(3)), PieceDrafts.align(listOf("a", "b", "c", "d"), listOf("a", "b c", "d")))
    }

    @Test
    fun insertedPieceIsNew() {
        assertEquals(listOf(o(0), o(null), o(1)), PieceDrafts.align(listOf("a", "c"), listOf("a", "b", "c")))
        assertEquals(listOf(o(null), o(null)), PieceDrafts.align(emptyList(), listOf("a", "b")))
    }

    @Test
    fun movedAndRepeatedLinesAlignByCommonSubsequence() {
        // a refrain repeated: the second "r" is the inserted one
        assertEquals(listOf(o(0), o(1), o(null), o(2)), PieceDrafts.align(listOf("r", "x", "y"), listOf("r", "x", "r", "y")))
        assertEquals(emptyList(), PieceDrafts.align(listOf("a", "b"), emptyList()))
    }

    @Test
    fun keptLinesStayWholeInProse() {
        val content = "Раз. Два.\nТри. Четыре."
        assertEquals(listOf("Раз.", "Два.", "Три.", "Четыре."), PieceDrafts.split(content, TextKind.PROSE))
        // "Раз. Два." was joined by hand: it stays one piece while its line is unchanged
        assertEquals(listOf("Раз. Два.", "Три.", "Четыре."), PieceDrafts.split(content, TextKind.PROSE, setOf("Раз. Два.")))
        assertEquals(listOf("Раз. Два.", "Три. Четыре."), PieceDrafts.split(content, TextKind.POEM))
    }

    @Test
    fun cutSnapsToTheNearerWordEdge() {
        val t = "Мороз и солнце; день"
        assertEquals(8, PieceDrafts.cutPoint(t, 10, noSpaces = false)) // "со|лнце" → before the word
        assertEquals(15, PieceDrafts.cutPoint(t, 13, noSpaces = false)) // "солнц|е;" → after it
        assertEquals(7, PieceDrafts.cutPoint(t, 7, noSpaces = false)) // already at a space
        assertEquals(3, PieceDrafts.cutPoint("春眠不覚暁", 3, noSpaces = true))
    }

    @Test
    fun punctuationOnlyHasNoPieces() {
        assertEquals(emptyList(), PieceDrafts.split("* * *\n—\n", TextKind.POEM, setOf("* * *")))
    }
}
