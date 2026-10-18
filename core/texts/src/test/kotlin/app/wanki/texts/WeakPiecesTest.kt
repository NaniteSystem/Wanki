package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeakPiecesTest {
    @Test fun twoErrorsInLastFiveIsWeak() {
        val o = (1L..5L).map { PieceOutcome("a", it, it == 2L || it == 4L) } + (1L..5L).map { PieceOutcome("b", it, it == 1L) }
        assertEquals(setOf("a"), WeakPieces.weak(o))
    }

    @Test fun oldErrorsAgeOut() = assertTrue(WeakPieces.weak((1L..7L).map { PieceOutcome("a", it, it <= 2L) }).isEmpty())

    @Test fun dailyLoad() {
        assertEquals(4, TextLoad.daily(TextSchedule.FSRS, 4, 7))
        assertEquals(32, TextLoad.daily(TextSchedule.HIFZ, 4, 7))
    }
}
