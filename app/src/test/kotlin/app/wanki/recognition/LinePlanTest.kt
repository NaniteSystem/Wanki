package app.wanki.recognition

import app.wanki.data.PageLineEntity
import app.wanki.ink.InkRect
import app.wanki.recognition.LinePlan.LineInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinePlanTest {
    private fun stored(key: Long, index: Int, text: String, corrected: String? = null) =
        PageLineEntity("p", key, index, 0f, index * 10f, 50f, index * 10f + 8f, text, corrected, 1L)

    private fun input(key: Long, index: Int) = LineInput(key, index, InkRect(0f, index * 10f, 50f, index * 10f + 8f))

    private val three = listOf(stored(1, 0, "a", corrected = "A"), stored(2, 1, "b"), stored(3, 2, "c"))

    @Test
    fun unchangedLinesKept() {
        val plan = LinePlan.plan(listOf(input(1, 0), input(2, 1), input(3, 2)), three)
        assertTrue(plan.recognize.isEmpty())
        assertEquals(three, plan.keep)
        assertEquals("A", plan.keep[0].corrected)
    }

    @Test
    fun changedLineOnly() {
        val plan = LinePlan.plan(listOf(input(1, 0), input(22, 1), input(3, 2)), three)
        assertEquals(listOf(input(22, 1)), plan.recognize)
        assertEquals(listOf(1L, 3L), plan.keep.map { it.lineKey })
        assertEquals(listOf(0, 2), plan.keep.map { it.lineIndex })
    }

    @Test
    fun correctionDroppedWhenLineChanges() {
        val plan = LinePlan.plan(listOf(input(11, 0), input(2, 1), input(3, 2)), three)
        assertEquals(listOf(input(11, 0)), plan.recognize)
        assertTrue(plan.keep.none { it.corrected != null })
    }

    @Test
    fun reorderedLinesReindexed() {
        val plan = LinePlan.plan(listOf(input(3, 0), input(1, 1), input(2, 2)), three)
        assertTrue(plan.recognize.isEmpty())
        assertEquals(mapOf(3L to 0, 1L to 1, 2L to 2), plan.keep.associate { it.lineKey to it.lineIndex })
        assertEquals(10f, plan.keep.first { it.lineKey == 1L }.top)
        assertEquals("A", plan.keep.first { it.lineKey == 1L }.corrected)
    }

    @Test
    fun identicalKeyYieldsOneRow() {
        val plan = LinePlan.plan(listOf(input(1, 0), input(1, 1)), emptyList())
        assertEquals(listOf(input(1, 0)), plan.recognize)
    }

    @Test
    fun matchFoldsCaseAndYo() {
        assertTrue(LineMatch.matches("Всё хорошо", "все"))
        assertTrue(LineMatch.matches("Hello World", "WORLD"))
        assertTrue(!LineMatch.matches("abc", " "))
    }

    @Test
    fun searchTextKeepsOldContentWhileLinesFail() {
        val ok = listOf(stored(1, 0, "a"))
        // nothing failed: the lines
        assertEquals("a", SearchText.build(ok, emptyList(), emptyList(), "old"))
        // a legacy page: the failed line has no old row, so the old text stays whole
        assertEquals("old text", SearchText.build(ok, listOf(1), emptyList(), "old text"))
        assertEquals("old text", SearchText.build(emptyList(), listOf(0, 1), emptyList(), "old text"))
        // a changed line failed: its old row fills the place
        assertEquals("a\nb", SearchText.build(ok, listOf(1), listOf(stored(9, 1, "b")), "old"))
    }
}
