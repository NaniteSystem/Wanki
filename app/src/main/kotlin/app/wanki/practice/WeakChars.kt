package app.wanki.practice

import app.wanki.data.CharScoreEntity

/** A character with its recent handwriting quality. */
data class CharQuality(
    val glyph: String,
    val language: String,
    /** Average beauty of the last [WeakChars.WINDOW] attempts. */
    val average: Double,
    val attempts: Int,
    val lastAt: Long,
) {
    val weak: Boolean get() = attempts >= WeakChars.MIN_ATTEMPTS && average < WeakChars.THRESHOLD
}

/**
 * The "practise handwriting" queue: characters whose recent handwriting is consistently below
 * [THRESHOLD]. Only the latest attempts count, so practice moves a character out of the queue.
 */
object WeakChars {
    const val THRESHOLD = 75.0
    const val WINDOW = 5
    const val MIN_ATTEMPTS = 2

    /** [scores] in any order; rows without a beauty score are ignored. Weakest first. */
    fun quality(scores: List<CharScoreEntity>): List<CharQuality> =
        scores.filter { it.beauty != null }
            .groupBy { it.glyph }
            .map { (glyph, rows) ->
                val recent = rows.sortedByDescending { it.at }.take(WINDOW)
                CharQuality(glyph, recent.first().language, recent.mapNotNull { it.beauty }.average(), rows.size, recent.first().at)
            }
            .sortedWith(compareBy({ it.average }, { -it.lastAt }))

    fun weak(scores: List<CharScoreEntity>): List<CharQuality> = quality(scores).filter { it.weak }
}
