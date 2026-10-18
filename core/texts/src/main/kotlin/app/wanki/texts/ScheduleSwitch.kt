package app.wanki.texts

import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.srs.Review
import app.wanki.srs.SrsState

/** One recorded recall of a piece, kept so FSRS state can be rebuilt after a schedule switch. */
data class PieceLog(val pieceId: String, val at: Long, val rating: Rating)

/** Converts between the FSRS and hifz schedules without losing history. */
object ScheduleSwitch {
    /** Learned pieces mapped to their remaining sabqi days; older ones go to manzil (0). */
    fun toHifz(pieces: List<PlanPiece>, today: Long, window: Int): Map<String, Int> =
        pieces.filter { it.learnedDay != null }.associate {
            val days = today - it.learnedDay!!
            it.id to if (days < window) (window - days).toInt() else 0
        }

    /** FSRS state per piece as a replay of its log. */
    fun toFsrs(logs: List<PieceLog>, scheduler: FsrsScheduler, seeds: Map<String, Long>): Map<String, SrsState> =
        logs.groupBy { it.pieceId }.mapValues { (id, l) ->
            scheduler.replay(l.map { Review(it.rating, it.at) }, seeds[id] ?: 0)
        }
}
