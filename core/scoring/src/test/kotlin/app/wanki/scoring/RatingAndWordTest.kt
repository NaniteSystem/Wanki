package app.wanki.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatingAndWordTest {
    private val policy = RatingPolicy()

    @Test
    fun `rating depends on recall, hints and time only`() {
        assertEquals(Rating.AGAIN, policy.suggest(passed = false, usedHint = false, durationMs = 1000, strokeCount = 5))
        assertEquals(Rating.HARD, policy.suggest(passed = true, usedHint = true, durationMs = 1000, strokeCount = 5))
        assertEquals(Rating.EASY, policy.suggest(passed = true, usedHint = false, durationMs = 4000, strokeCount = 5))
        assertEquals(Rating.GOOD, policy.suggest(passed = true, usedHint = false, durationMs = 9000, strokeCount = 5))
        assertEquals(Rating.HARD, policy.suggest(passed = true, usedHint = false, durationMs = 30000, strokeCount = 5))
    }

    @Test
    fun `word fails if any character fails`() {
        val nichi = Fixtures.kanji("日")
        val hon = Fixtures.kanji("本")
        val scorer = WordScorer()
        val good = scorer.score(listOf(nichi, hon), listOf(CellAttempt(Fixtures.perfect(nichi)), CellAttempt(Fixtures.perfect(hon))))
        assertTrue(good.passed)
        val bad = scorer.score(listOf(nichi, hon), listOf(CellAttempt(Fixtures.perfect(nichi)), CellAttempt(emptyList())))
        assertFalse(bad.passed)
        assertEquals(0.0, bad.minScore)
    }
}
