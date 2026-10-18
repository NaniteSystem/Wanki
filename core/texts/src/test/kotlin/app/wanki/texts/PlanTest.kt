package app.wanki.texts

import app.wanki.srs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun piece(i: Int, learned: Long? = null, left: Int = 0, hifzDay: Long? = null, due: Long = 0) = PlanPiece("p$i", i, learned, due, left, hifzDay)

fun text(
    vararg p: PlanPiece,
    schedule: TextSchedule = TextSchedule.HIFZ,
    active: Boolean = true,
    size: Int = 2,
    order: Long = 0,
    lastPortionDay: Long? = null,
    id: String = "t",
) = PlanText(id, schedule, active, size, order, lastPortionDay, p.toList())

class PlanTest {
    @Test fun ratingRule() {
        assertEquals(Rating.AGAIN, TextRating.rating(RecallOutcome.FORGOT, false))
        assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.ERRORS, false))
        assertEquals(Rating.GOOD, TextRating.rating(RecallOutcome.CORRECT, false))
        assertEquals(Rating.EASY, TextRating.rating(RecallOutcome.EASY, false))
        assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.CORRECT, true))
        assertEquals(Rating.HARD, TextRating.rating(RecallOutcome.EASY, true))
    }

    @Test fun portionIsNextUnlearnedPieces() =
        assertEquals(listOf("p1", "p2"), Portions.next(text(piece(0, learned = 1), piece(1), piece(2), piece(3)), today = 10))

    @Test fun onePortionPerDay() =
        assertTrue(Portions.next(text(piece(0), piece(1), lastPortionDay = 10), today = 10).isEmpty())

    @Test fun pausedTextGetsNoPortionButKeepsReviews() {
        val t = text(piece(0, learned = 1, left = 3), piece(1), active = false)
        assertTrue(Portions.next(t, 10).isEmpty())
        assertEquals(listOf("p0"), HifzPlan.sabqi(t, 10))
        val f = text(piece(0, learned = 1, due = 500), piece(1), active = false, schedule = TextSchedule.FSRS)
        assertEquals(listOf("p0"), FsrsTextPlan.due(f, until = 1000))
    }

    @Test fun dueIsLearnedAndInOrder() = assertEquals(
        listOf("p0", "p2"),
        FsrsTextPlan.due(
            text(
                piece(0, learned = 1, due = 900), piece(1, learned = 1, due = 2000),
                piece(2, learned = 1, due = 100), piece(3, due = 0), schedule = TextSchedule.FSRS,
            ),
            until = 1000,
        ),
    )
}
