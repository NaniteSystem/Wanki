package app.wanki.practice

import app.wanki.data.CharScoreEntity
import app.wanki.data.ScoreSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeakCharsTest {
    private fun score(glyph: String, at: Long, beauty: Double?) =
        CharScoreEntity(glyph = glyph, language = "ja", at = at, score = 90.0, beauty = beauty, source = ScoreSource.REVIEW.name)

    @Test
    fun consistentlyLowHandwritingIsWeak() {
        val weak = WeakChars.weak(listOf(score("日", 1, 60.0), score("日", 2, 70.0), score("本", 1, 90.0), score("本", 2, 85.0)))
        assertEquals(listOf("日"), weak.map { it.glyph })
        assertEquals(65.0, weak[0].average)
    }

    @Test
    fun oneBadAttemptIsNotEnough() {
        assertTrue(WeakChars.weak(listOf(score("日", 1, 40.0))).isEmpty())
    }

    @Test
    fun onlyTheLatestAttemptsCountSoPracticeHelps() {
        val old = (1L..5L).map { score("木", it, 50.0) }
        val recent = (6L..10L).map { score("木", it, 90.0) }
        assertTrue(WeakChars.weak(old + recent).isEmpty())
        assertEquals(10, WeakChars.quality(old + recent).single().attempts)
    }

    @Test
    fun attemptsWithoutBeautyAreIgnored() {
        assertTrue(WeakChars.quality(listOf(score("口", 1, null), score("口", 2, null))).isEmpty())
    }

    @Test
    fun weakestFirst() {
        val q = WeakChars.quality(listOf(score("a", 1, 80.0), score("b", 1, 50.0), score("c", 1, 65.0)))
        assertEquals(listOf("b", "c", "a"), q.map { it.glyph })
    }
}
