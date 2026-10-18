package app.wanki.texts

import app.wanki.srs.Rating

/** One piece of a text as the planner sees it; the repository maps database rows to this. */
data class PlanPiece(
    val id: String,
    val index: Int,
    val learnedDay: Long?, // epoch day it was learned; null = not learned
    val due: Long = 0, // FSRS due, epoch ms (FSRS texts)
    val sabqiLeft: Int = 0, // hifz: sabqi recitations left; 0 = in manzil
    val hifzDay: Long? = null, // hifz: last epoch day counted in sabqi/manzil
)

/** A text with its pieces (sorted by index) and planning settings. */
data class PlanText(
    val id: String,
    val schedule: TextSchedule,
    val active: Boolean,
    val portionSize: Int,
    val order: Long,
    val lastPortionDay: Long?,
    val pieces: List<PlanPiece>,
)

/** How well a piece was recalled, as the user reports it. */
enum class RecallOutcome { FORGOT, ERRORS, CORRECT, EASY }

/** Maps a recall outcome to the FSRS rating; using hints caps the rating at Hard. */
object TextRating {
    fun rating(outcome: RecallOutcome, usedHints: Boolean): Rating {
        val r = when (outcome) {
            RecallOutcome.FORGOT -> Rating.AGAIN
            RecallOutcome.ERRORS -> Rating.HARD
            RecallOutcome.CORRECT -> Rating.GOOD
            RecallOutcome.EASY -> Rating.EASY
        }
        return if (usedHints && r > Rating.HARD) Rating.HARD else r
    }
}

/** New material: one portion of unlearned pieces per day, none while the text is paused. */
object Portions {
    fun next(text: PlanText, today: Long): List<String> {
        if (!text.active || text.lastPortionDay == today) return emptyList()
        return text.pieces.filter { it.learnedDay == null }.take(text.portionSize).map { it.id }
    }
}

/** FSRS texts: learned pieces that are due; paused texts keep their reviews. */
object FsrsTextPlan {
    fun due(text: PlanText, until: Long): List<String> =
        text.pieces.filter { it.learnedDay != null && it.due <= until }.sortedBy { it.index }.map { it.id }
}
