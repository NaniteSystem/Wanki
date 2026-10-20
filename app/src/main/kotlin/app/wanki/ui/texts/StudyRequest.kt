package app.wanki.ui.texts

import app.wanki.data.TextPieceEntity
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
 * The cue level after "Дальше". A level passed with no revealed words rises on its own; revealing a
 * word keeps you on the level to try it again, and the button [forced] forces the step regardless.
 */
fun nextCueLevel(level: Int, revealedThisLevel: Boolean, forced: Boolean): Int =
    if (forced || !revealedThisLevel) (level + 1).coerceIn(0, VanishingCues.MAX_LEVEL) else level

/** A learn portion counts as learned only when nothing was revealed and no piece is marked wrong. */
fun isClean(hints: Int, markedErrors: Set<String>): Boolean = hints == 0 && markedErrors.isEmpty()

/** The errors of a recital: pieces marked by hand plus every piece a word was revealed in. */
fun recitalErrors(marked: Set<String>, revealed: Set<WordRef>): Set<String> =
    marked + revealed.map { it.pieceId }

/** Consecutive pieces grouped by their text, so manzil can show a title before each text's run. */
fun groupByText(pieces: List<TextPieceEntity>): List<Pair<String, List<TextPieceEntity>>> {
    val groups = ArrayList<Pair<String, List<TextPieceEntity>>>()
    for (p in pieces) {
        val last = groups.lastOrNull()
        if (last != null && last.first == p.textId) groups[groups.lastIndex] = last.first to (last.second + p)
        else groups.add(p.textId to listOf(p))
    }
    return groups
}
