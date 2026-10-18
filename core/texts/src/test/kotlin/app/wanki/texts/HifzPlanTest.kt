package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HifzPlanTest {
    @Test fun learnedTodayIsNotInTodaysSabqi() =
        assertTrue(HifzPlan.sabqi(text(HifzPlan.learned(piece(0), 10, 7)), 10).isEmpty())

    @Test fun sabqiCountsOncePerDay() {
        val once = HifzPlan.afterSabqi(piece(0, learned = 9, left = 7), error = false, today = 10, window = 7)
        assertEquals(6, once.sabqiLeft)
        assertEquals(6, HifzPlan.afterSabqi(once, error = false, today = 10, window = 7).sabqiLeft)
        assertTrue(HifzPlan.sabqi(text(once), 10).isEmpty())
        assertEquals(listOf("p0"), HifzPlan.sabqi(text(once), 11))
    }

    @Test fun errorRestartsSabqiWindow() =
        assertEquals(7, HifzPlan.afterSabqi(piece(0, learned = 1, left = 2), true, 10, 7).sabqiLeft)

    @Test fun afterSevenCleanDaysItIsManzil() {
        var p = HifzPlan.learned(piece(0), 0, 7)
        for (d in 1L..7L) p = HifzPlan.afterSabqi(p, false, d, 7)
        assertEquals(0, p.sabqiLeft)
        assertTrue(HifzPlan.sabqi(text(p), 8).isEmpty())
        assertEquals(listOf("p0"), HifzPlan.manzilChunk(listOf(text(p)), null, 14))
    }

    @Test fun manzilErrorGoesBackToSabqi() =
        assertEquals(7, HifzPlan.afterManzil(piece(0, learned = 1), true, 20, 7).sabqiLeft)

    @Test fun manzilChunkIsShareOfCycle() {
        val t = text(*Array(30) { piece(it, learned = 1) })
        assertEquals((0 until 3).map { "p$it" }, HifzPlan.manzilChunk(listOf(t), null, 14)) // ceil(30/14) = 3
        assertEquals((3 until 6).map { "p$it" }, HifzPlan.manzilChunk(listOf(t), "p3", 14))
    }

    @Test fun manzilWrapsAroundAndNeverRepeats() {
        val t = text(piece(0, learned = 1), piece(1, learned = 1), piece(2, learned = 1))
        assertEquals(listOf("p2", "p0"), HifzPlan.manzilChunk(listOf(t), "p2", 2))
        assertEquals("p1", HifzPlan.nextPointer(listOf(t), listOf("p2", "p0")))
        assertEquals(listOf("p0", "p1", "p2"), HifzPlan.manzilChunk(listOf(t), "p0", 1))
    }

    @Test fun manzilGoesAcrossTextsInOrderAndSkipsFsrsTexts() {
        val a = text(piece(0, learned = 1), id = "a", order = 2)
        val b = text(PlanPiece("b0", 0, 1), id = "b", order = 1)
        val f = text(PlanPiece("f0", 0, 1), id = "f", order = 0, schedule = TextSchedule.FSRS)
        assertEquals(listOf("b0", "p0"), HifzPlan.manzilChunk(listOf(a, b, f), null, 1))
    }

    @Test fun pointerToMissingPieceStartsFromFirst() =
        assertEquals(listOf("p0"), HifzPlan.manzilChunk(listOf(text(piece(0, learned = 1), piece(1, learned = 1))), "gone", 2))

    @Test fun noManzilPiecesNoPointer() =
        assertNull(HifzPlan.nextPointer(listOf(text(piece(0))), emptyList()))
}
