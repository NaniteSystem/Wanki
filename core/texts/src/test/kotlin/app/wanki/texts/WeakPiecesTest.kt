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

    /** Typed runs keep a history per word: a word wrong in two of its last runs makes its piece weak. */
    @Test fun wordsWithRepeatedErrorsMakeThePieceWeak() {
        val words = mapOf(
            "a" to listOf(WordStat(recent = 0b001), WordStat(recent = 0b100)),
            "b" to listOf(WordStat(), WordStat(recent = 0b101)),
            "c" to listOf(WordStat(cleanStreak = 3)),
        )
        val clean = listOf(PieceOutcome("a", 1, false), PieceOutcome("b", 1, false))
        assertEquals(setOf("b"), WeakPieces.weak(clean, words))
    }

    @Test fun dailyLoad() {
        assertEquals(4, TextLoad.daily(TextSchedule.FSRS, 4, 7))
        assertEquals(32, TextLoad.daily(TextSchedule.HIFZ, 4, 7))
    }
}
