package app.wanki.ui.texts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.wanki.data.TextPieceEntity
import app.wanki.data.WordKey
import app.wanki.texts.PieceRun
import app.wanki.texts.PieceWord
import app.wanki.texts.SampleLevel
import app.wanki.texts.TypingMatcher
import app.wanki.texts.TypingResult
import app.wanki.texts.TypingText
import app.wanki.texts.UnitState
import app.wanki.texts.WordHistory
import app.wanki.texts.WordLook
import app.wanki.texts.WordOutcome
import app.wanki.texts.WordStat

/** A finished typed run: per-piece counts for the rating, per-word outcomes for the history. */
data class TypedRun(
    val pieces: Map<String, PieceRun>,
    val words: Map<WordKey, WordOutcome>,
    val errorPieces: Set<String>,
    val hintedPieces: Set<String>,
)

/** One unfinished attempt is kept per request; a review keeps one per piece. */
fun draftKey(request: StudyRequest, currentPieceId: String?): String =
    "${request.kind}:${currentPieceId ?: request.pieceIds.joinToString(",")}"

/** First learning types over the sample; every recall starts from memory. */
fun defaultLevel(kind: StudyKind): SampleLevel = if (kind == StudyKind.LEARN) SampleLevel.SAMPLE else SampleLevel.MEMORY

/**
 * Typing [pieces] in order as one text. Only the committed text is judged; IME composition is shown but not
 * checked. Hints (the «Образец» button, memory cues reached by the caret) and typing with the sample stay
 * recorded for the rest of the run, whatever the level is switched to later.
 */
class TypingSession(
    val pieces: List<TextPieceEntity>,
    words: Map<String, List<PieceWord>>,
    private val stats: Map<WordKey, WordStat>,
    level: SampleLevel,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The words of each piece, in the order they are typed. */
    val pieceWords: Map<String, List<PieceWord>> = pieces.associate { it.id to words[it.id].orEmpty() }

    /** Unit index → word; units run over all pieces in request order. */
    val keys: List<WordKey> = pieces.flatMap { p -> pieceWords.getValue(p.id).indices.map { WordKey(p.id, it) } }
    private val unitOf: Map<WordKey, Int> = keys.withIndex().associate { it.value to it.index }

    /** A word whose reading has nothing checkable cannot be typed; it never counts against the run. */
    private val empty = BooleanArray(keys.size) { TypingText.fold(word(keys[it]).reading).chars.isEmpty() }
    private val matcher = TypingMatcher(keys.map { word(it).unit() })

    var result: TypingResult by mutableStateOf(matcher.update(""))
        private set
    var committed by mutableStateOf("")
        private set
    var composing by mutableStateOf("")
        private set
    var sampleShown by mutableStateOf(false)
        private set
    var startedAt: Long = clock()

    private val hinted = LinkedHashSet<WordKey>()
    private val samplePieces = HashSet<String>()
    /** Units before this index have already been at (or behind) the caret. */
    private var reached = 0

    private var levelValue by mutableStateOf(level)
    var level: SampleLevel
        get() = levelValue
        set(v) {
            levelValue = v
            noteCaret()
        }

    init {
        noteCaret()
    }

    val elapsedMs: Long get() = (clock() - startedAt).coerceAtLeast(0)

    /** The first unit not judged yet (where the caret is), or [keys].size when everything is judged. */
    val caretUnit: Int
        get() {
            val units = result.units
            for (k in units.indices) if (units[k].state == UnitState.PENDING || units[k].state == UnitState.UNTYPED) return k
            return units.size
        }

    /** Input only goes to the end: the caret is forced there, the IME composition is kept. */
    fun onValue(v: TextFieldValue): TextFieldValue {
        val comp = v.composition
        val c = if (comp != null && !comp.collapsed) v.text.removeRange(comp.min, comp.max) else v.text
        composing = if (comp != null && !comp.collapsed) v.text.substring(comp.min, comp.max) else ""
        if (c != committed) {
            committed = c
            result = matcher.update(c)
            noteCaret()
        }
        return TextFieldValue(v.text, TextRange(v.text.length), comp)
    }

    /** Replaces the committed text at once (a resumed attempt). */
    fun restore(typed: String) {
        onValue(TextFieldValue(typed, TextRange(typed.length)))
    }

    fun look(pieceId: String, index: Int): WordLook {
        val key = WordKey(pieceId, index)
        val k = unitOf[key] ?: return WordLook.NONE
        val caret = caretUnit
        if (sampleShown && k >= caret) return WordLook.SHOWN
        return WordHistory.look(level, stats[key], atCaret = k == caret, passed = k < caret)
    }

    /** «Образец»: every not-yet-typed word it reveals is a hint. */
    fun showSample() {
        if (sampleShown) return
        val caret = caretUnit
        for (k in caret until keys.size) {
            val revealed = when (WordHistory.look(level, stats[keys[k]], atCaret = k == caret, passed = false)) {
                WordLook.SHOWN, WordLook.HIGHLIGHTED, WordLook.CUE_WORD -> false
                else -> true
            }
            if (revealed && !empty[k]) hinted += keys[k]
        }
        sampleShown = true
    }

    fun hideSample() {
        sampleShown = false
    }

    /** A memory cue counts once its word is reached; typing with the sample marks every piece still ahead. */
    private fun noteCaret() {
        val caret = caretUnit
        if (level == SampleLevel.MEMORY) {
            for (k in minOf(reached, caret)..minOf(caret, keys.size - 1)) {
                if (!empty[k] && WordHistory.countsAsHint(WordHistory.look(level, stats[keys[k]], atCaret = true, passed = false))) hinted += keys[k]
            }
        }
        if (level == SampleLevel.SAMPLE) for (k in caret until keys.size) samplePieces += keys[k].pieceId
        reached = maxOf(reached, caret + 1)
    }

    /**
     * The run as it stands: words not reached are skipped, a half-typed word is wrong. Errors count per word,
     * a row of skipped words is one error, an extra typed run is one more; nothing in the session changes.
     */
    fun finish(): TypedRun {
        val res = result
        val outcomes = LinkedHashMap<WordKey, WordOutcome>()
        val count = HashMap<String, Int>()
        val correct = HashMap<String, Int>()
        val errors = HashMap<String, Int>()
        var inSkip = false
        var lastPiece: String? = null
        fun error(piece: String) { errors[piece] = (errors[piece] ?: 0) + 1 }
        for ((k, key) in keys.withIndex()) {
            val piece = key.pieceId
            if (piece != lastPiece) { inSkip = false; lastPiece = piece }
            val unit = res.units[k]
            count[piece] = (count[piece] ?: 0) + 1
            if (unit.extraBefore != null) { error(piece); inSkip = false }
            val outcome = when {
                empty[k] -> WordOutcome.CORRECT
                unit.state == UnitState.OK -> WordOutcome.CORRECT
                unit.state == UnitState.WRONG -> WordOutcome.WRONG
                unit.state == UnitState.PENDING && unit.typed != null -> WordOutcome.WRONG
                else -> WordOutcome.SKIPPED
            }
            when (outcome) {
                WordOutcome.CORRECT -> { correct[piece] = (correct[piece] ?: 0) + 1; inSkip = false }
                WordOutcome.WRONG -> { error(piece); inSkip = false }
                WordOutcome.SKIPPED -> { if (!inSkip) error(piece); inSkip = true }
            }
            outcomes[key] = outcome
        }
        if (res.trailingExtra != null && keys.isNotEmpty()) error(keys[minOf(caretUnit, keys.size - 1)].pieceId)
        val hintsBy = hinted.groupingBy { it.pieceId }.eachCount()
        val runs = pieces.associate { p ->
            p.id to PieceRun(
                words = count[p.id] ?: 0, correct = correct[p.id] ?: 0, errors = errors[p.id] ?: 0,
                hints = hintsBy[p.id] ?: 0, withSample = p.id in samplePieces,
            )
        }
        return TypedRun(
            pieces = runs,
            words = outcomes,
            errorPieces = runs.filterValues { it.errors > 0 }.keys,
            hintedPieces = hintsBy.keys,
        )
    }

    private fun word(key: WordKey): PieceWord = pieceWords.getValue(key.pieceId)[key.index]
}
