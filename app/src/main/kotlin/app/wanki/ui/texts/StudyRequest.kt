package app.wanki.ui.texts

import app.wanki.data.TodayItem
import app.wanki.texts.VanishingCues

/** How a text (or portion of it) is studied; it selects the session rules and what gets written back. */
enum class StudyKind { LEARN, REVIEW, SABQI, MANZIL, PRACTICE }

/** How a piece is shown while studying it. */
enum class StudyMode { READ, CUES, FIRST_LETTERS, CHAIN, RECITE }

/** What to study: the [kind], the text it belongs to ([textId] is null only for manzil, which may span texts) and the [pieceIds]. */
data class StudyRequest(val kind: StudyKind, val textId: String?, val pieceIds: List<String>)

/** Today's plan row as a study session. */
fun TodayItem.toStudyRequest(): StudyRequest = when (this) {
    is TodayItem.NewPortion -> StudyRequest(StudyKind.LEARN, text.id, pieceIds)
    is TodayItem.Review -> StudyRequest(StudyKind.REVIEW, text.id, pieceIds)
    is TodayItem.Sabqi -> StudyRequest(StudyKind.SABQI, text.id, pieceIds)
    is TodayItem.Manzil -> StudyRequest(StudyKind.MANZIL, null, pieceIds)
}

/** One word inside a piece: the piece and the word's index from the language's breaker. */
data class WordRef(val pieceId: String, val index: Int)

/**
 * The cue level to show next. A level passed with no reveals ([revealedThisLevel] false) rises on its
 * own — this is the automatic path, called with [forced] = false. Once a word has been revealed the
 * level stays (so it can be retried); only [forced] = true (the "Дальше" button) advances it then.
 */
fun nextCueLevel(level: Int, revealedThisLevel: Boolean, forced: Boolean): Int =
    if (forced || !revealedThisLevel) (level + 1).coerceIn(0, VanishingCues.MAX_LEVEL) else level

/** A learn portion counts as learned only when nothing was revealed and no piece is marked wrong. */
fun isClean(hints: Int, markedErrors: Set<String>): Boolean = hints == 0 && markedErrors.isEmpty()

/** The errors of a recital: pieces marked by hand plus every piece a word was revealed in. */
fun recitalErrors(marked: Set<String>, revealed: Set<WordRef>): Set<String> =
    marked + revealed.map { it.pieceId }
