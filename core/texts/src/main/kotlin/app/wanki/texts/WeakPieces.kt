package app.wanki.texts

/** One recitation result of a piece; [error] is true when it was missed or wrong. */
data class PieceOutcome(val pieceId: String, val at: Long, val error: Boolean)

/** Pieces with repeated recent mistakes. */
object WeakPieces {
    const val RECENT = 5
    const val ERRORS = 2

    /**
     * A piece is weak with [ERRORS] errors among its last [RECENT] recitations, or with a word typed wrong in
     * [ERRORS] of its recent typed runs ([words]: the word history of each piece).
     */
    fun weak(outcomes: List<PieceOutcome>, words: Map<String, Collection<WordStat>> = emptyMap()): Set<String> {
        val byRecitals = outcomes.groupBy { it.pieceId }
            .filterValues { l -> l.sortedByDescending { it.at }.take(RECENT).count { it.error } >= ERRORS }
            .keys
        val byWords = words.filterValues { l -> l.any { Integer.bitCount(it.recent) >= ERRORS } }.keys
        return byRecitals + byWords
    }
}

/** Daily-load hint for the text editor. */
object TextLoad {
    /** Pieces handled per day: a portion, plus (hifz) its sabqi over the whole window. */
    fun daily(schedule: TextSchedule, portionSize: Int, window: Int): Int = when (schedule) {
        TextSchedule.FSRS -> portionSize
        TextSchedule.HIFZ -> portionSize + portionSize * window
    }
}
