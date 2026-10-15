package app.wanki.scoring

enum class Rating { AGAIN, HARD, GOOD, EASY }

/**
 * Maps a writing attempt to an FSRS rating.
 *
 * Only recall drives the rating: below the threshold it is Again; above it, hints and time decide
 * between Hard/Good/Easy. Handwriting quality is deliberately NOT used here — an ugly but correct
 * character must not shorten intervals. It is tracked separately ([BeautyResult]).
 */
data class RatingPolicy(
    val easyBaseMs: Long = 1_500,
    val easyMsPerStroke: Long = 700,
    val hardBaseMs: Long = 6_000,
    val hardMsPerStroke: Long = 2_500,
) {
    fun suggest(passed: Boolean, usedHint: Boolean, durationMs: Long, strokeCount: Int): Rating = when {
        !passed -> Rating.AGAIN
        usedHint -> Rating.HARD
        durationMs > hardBaseMs + hardMsPerStroke * strokeCount -> Rating.HARD
        durationMs <= easyBaseMs + easyMsPerStroke * strokeCount -> Rating.EASY
        else -> Rating.GOOD
    }

    fun suggest(result: ScoreResult, usedHint: Boolean, durationMs: Long, referenceStrokeCount: Int) =
        suggest(result.passed, usedHint, durationMs, referenceStrokeCount)
}
