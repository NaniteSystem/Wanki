package app.wanki.texts

/** One recitation result of a piece; [error] is true when it was missed or wrong. */
data class PieceOutcome(val pieceId: String, val at: Long, val error: Boolean)

/** Pieces with repeated recent mistakes. */
object WeakPieces {
    const val RECENT = 5
    const val ERRORS = 2

    fun weak(outcomes: List<PieceOutcome>): Set<String> =
        outcomes.groupBy { it.pieceId }
            .filterValues { l -> l.sortedByDescending { it.at }.take(RECENT).count { it.error } >= ERRORS }
            .keys
}

/** Daily-load hint for the text editor. */
object TextLoad {
    /** Pieces handled per day: a portion, plus (hifz) its sabqi over the whole window. */
    fun daily(schedule: TextSchedule, portionSize: Int, window: Int): Int = when (schedule) {
        TextSchedule.FSRS -> portionSize
        TextSchedule.HIFZ -> portionSize + portionSize * window
    }
}
