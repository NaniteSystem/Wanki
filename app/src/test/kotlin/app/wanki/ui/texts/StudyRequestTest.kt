package app.wanki.ui.texts

import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.data.TodayItem
import app.wanki.texts.VanishingCues
import kotlin.test.Test
import kotlin.test.assertEquals

class StudyRequestTest {
    private fun text(id: String) = TextEntity(id = id, title = "T", language = "ru")
    private fun piece(id: String, textId: String, index: Int = 0) = TextPieceEntity(id = id, textId = textId, pieceIndex = index, content = "x")

    @Test fun todayItemsBecomeTheRightKind() {
        assertEquals(StudyRequest(StudyKind.LEARN, "t", listOf("a", "b")), TodayItem.NewPortion(text("t"), listOf("a", "b")).toStudyRequest())
        assertEquals(StudyRequest(StudyKind.REVIEW, "t", listOf("a")), TodayItem.Review(text("t"), listOf("a")).toStudyRequest())
        assertEquals(StudyRequest(StudyKind.SABQI, "t", listOf("a")), TodayItem.Sabqi(text("t"), listOf("a")).toStudyRequest())
        // manzil spans texts, so it carries no single textId
        assertEquals(StudyRequest(StudyKind.MANZIL, null, listOf("a", "b")), TodayItem.Manzil(listOf("a", "b")).toStudyRequest())
    }

    @Test fun cleanLevelRisesOnItsOwnDirtyLevelNeedsForcing() {
        assertEquals(2, nextCueLevel(1, revealedThisLevel = false, forced = false)) // passed with no reveals → up
        assertEquals(1, nextCueLevel(1, revealedThisLevel = true, forced = false))  // revealed a word → stays
        assertEquals(2, nextCueLevel(1, revealedThisLevel = true, forced = true))   // "Дальше" forces the step
    }

    @Test fun cueLevelClampsAtMax() {
        assertEquals(VanishingCues.MAX_LEVEL, nextCueLevel(VanishingCues.MAX_LEVEL, revealedThisLevel = false, forced = true))
    }

    @Test fun usedHintMeansNotClean() {
        assertEquals(true, isClean(0, emptySet()))
        assertEquals(false, isClean(1, emptySet()))          // a hint ⇒ not clean
        assertEquals(false, isClean(0, setOf("p1")))          // a marked mistake ⇒ not clean
    }

    @Test fun recitalErrorsAddRevealedPieces() {
        val revealed = setOf(WordRef("p2", 0), WordRef("p2", 3), WordRef("p3", 1))
        assertEquals(setOf("p1", "p2", "p3"), recitalErrors(marked = setOf("p1"), revealed = revealed))
    }

    @Test fun groupByTextKeepsConsecutiveRuns() {
        val pieces = listOf(piece("b0", "b"), piece("b1", "b"), piece("a0", "a"))
        val groups = groupByText(pieces)
        assertEquals(listOf("b", "a"), groups.map { it.first })
        assertEquals(listOf(2, 1), groups.map { it.second.size })
    }
}
