package app.wanki.texts

import app.wanki.srs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingScoreTest {
    private fun run(words: Int, correct: Int, errors: Int = 0, hints: Int = 0, sample: Boolean = false) =
        PieceRun(words, correct, errors, hints, sample)

    @Test fun cleanIsGood() = assertEquals(Rating.GOOD, TypingScore.rating(run(10, 10), false))
    @Test fun cleanEasyButton() = assertEquals(Rating.EASY, TypingScore.rating(run(10, 10), true))
    @Test fun errorsAreHard() = assertEquals(Rating.HARD, TypingScore.rating(run(10, 9, errors = 1), false))

    @Test fun belowSixtyIsAgain() {
        assertEquals(Rating.AGAIN, TypingScore.rating(run(10, 5, errors = 5), false))
        assertEquals(Rating.HARD, TypingScore.rating(run(10, 6, errors = 4), false))
    }

    @Test fun hintsCapHard() = assertEquals(Rating.HARD, TypingScore.rating(run(10, 10, hints = 1), true))
    @Test fun sampleCapsHard() = assertEquals(Rating.HARD, TypingScore.rating(run(10, 10, sample = true), true))
    @Test fun emptyPieceIsGood() = assertEquals(Rating.GOOD, TypingScore.rating(run(0, 0), false))

    @Test fun cleanFlag() {
        assertTrue(TypingScore.clean(run(10, 10)))
        assertFalse(TypingScore.clean(run(10, 10, hints = 1)))
        assertFalse(TypingScore.clean(run(10, 10, sample = true)))
    }
}
