package app.wanki.ui.texts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wanki.data.TodayItem
import app.wanki.texts.VanishingCues

/** How a text (or portion of it) is studied; it selects the session rules and what gets written back. */
enum class StudyKind { LEARN, REVIEW, SABQI, MANZIL, PRACTICE }

/** How a piece is shown while studying it. */
enum class StudyMode { READ, CUES, FIRST_LETTERS, CHAIN, RECITE, TYPE }

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
 * The cue level after "Дальше" ("I recited this level"). A level passed with no revealed words rises
 * by itself to the next one; a level where a word was revealed stays, to be recited again.
 */
fun nextCueLevel(level: Int, revealedThisLevel: Boolean): Int =
    if (revealedThisLevel) level.coerceIn(0, VanishingCues.MAX_LEVEL) else (level + 1).coerceIn(0, VanishingCues.MAX_LEVEL)

/** A learn portion counts as learned only when nothing was revealed and no piece is marked wrong. */
fun isClean(hints: Int, markedErrors: Set<String>): Boolean = hints == 0 && markedErrors.isEmpty()

/**
 * The errors of a recital: pieces marked by hand plus every piece a hint was taken in. [hinted] outlives
 * mode switches and reveals, so "used hint ⇒ not clean" cannot be bypassed.
 */
fun recitalErrors(marked: Set<String>, hinted: Set<String>): Set<String> = marked + hinted

/** A tap on a piece card toggles its mark; a [hinted] piece stays an error, so tapping it changes nothing. */
fun toggleMark(marked: Set<String>, hinted: Set<String>, pieceId: String): Set<String> = when (pieceId) {
    in hinted -> marked
    in marked -> marked - pieceId
    else -> marked + pieceId
}

/**
 * In a LEARN session the recital is judged on its own: entering RECITE starts a fresh attempt, so hints
 * taken while practising in other modes do not block «Без ошибок». Hints inside the recital still count.
 */
fun startsFreshRecital(kind: StudyKind, from: StudyMode, to: StudyMode): Boolean =
    kind == StudyKind.LEARN && to == StudyMode.RECITE && from != StudyMode.RECITE

/** The cue level the slider moves to, or null when it stays on the current one (then nothing is reset). */
fun cueJump(current: Int, requested: Int): Int? =
    requested.coerceIn(0, VanishingCues.MAX_LEVEL).takeIf { it != current }

/** Runs one save at a time: a second tap while the first is still writing is dropped. [busy] disables the buttons. */
class BusyGuard {
    var busy by mutableStateOf(false)
        private set

    suspend fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try {
            action()
        } finally {
            busy = false
        }
    }
}
