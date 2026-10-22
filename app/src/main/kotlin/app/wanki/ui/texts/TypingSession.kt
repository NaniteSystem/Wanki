package app.wanki.ui.texts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** A run in progress, as kept across activity recreation. */
data class TypingSnapshot(
    val typed: String, val level: SampleLevel, val elapsedMs: Long, val hinted: Set<WordKey>,
    val samplePieces: Set<String>, val sampleShown: Boolean, val ended: Boolean,
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
    val stats: Map<WordKey, WordStat>,
    level: SampleLevel,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The words of each piece, in the order they are typed. */
    val pieceWords: Map<String, List<PieceWord>> = pieces.associate { it.id to words[it.id].orEmpty() }

    /** Unit index → word; units run over all pieces in request order. */
    val keys: List<WordKey> = pieces.flatMap { p -> pieceWords.getValue(p.id).indices.map { WordKey(p.id, it) } }
    private val unitIndex: Map<WordKey, Int> = keys.withIndex().associate { it.value to it.index }

    /** A word whose reading has nothing checkable cannot be typed; it never counts against the run. */
    private val empty = BooleanArray(keys.size) { TypingText.fold(word(keys[it]).reading).chars.isEmpty() }
    private val matcher = TypingMatcher(keys.map { word(it).unit() })

    /** Where each unit's reading starts among the checked characters (the matcher's columns). */
    private val columnStart = IntArray(keys.size + 1).also { c ->
        for (k in keys.indices) c[k + 1] = c[k] + TypingText.fold(word(keys[k]).reading).chars.size
    }

    var result: TypingResult by mutableStateOf(matcher.update(""))
        private set
    /** The first unit not judged yet (where the caret is), or [keys].size when everything is judged. */
    var caretUnit by mutableIntStateOf(caretOf(result))
        private set
    /** Set by [end] or once the whole text is typed; input is ignored afterwards and the clock stops. */
    var ended by mutableStateOf(false)
        private set
    private var endedAt = 0L
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
            markSample(caretUnit)
        }

    init {
        noteCaret()
    }

    val elapsedMs: Long get() = ((if (ended) endedAt else clock()) - startedAt).coerceAtLeast(0)

    /** Words judged so far and how many of them are right, for the live accuracy. */
    val judged: Int get() = caretUnit
    val judgedCorrect: Int get() = (0 until caretUnit).count { result.units[it].state == UnitState.OK }

    /** How many checked characters of unit [k] are typed (for a half-typed word). */
    fun typedColumns(k: Int): Int = (result.end - columnStart[k]).coerceIn(0, columnStart[k + 1] - columnStart[k])

    fun unitOf(key: WordKey): Int? = unitIndex[key]

    private fun caretOf(r: TypingResult): Int {
        for (k in r.units.indices) if (r.units[k].state == UnitState.PENDING || r.units[k].state == UnitState.UNTYPED) return k
        return r.units.size
    }

    /**
     * The keyboard may hold the last word in its composition until a space: when the composition completes the
     * text and the last word is right, the run ends as if it were committed; otherwise it waits.
     */
    private fun compositionFinishes(): Boolean {
        if (keys.isEmpty()) return false
        val peek = matcher.update(typedAll)
        val done = peek.finished && peek.units.last().state == UnitState.OK
        if (!done) matcher.update(committed) // back to the committed rows
        return done
    }

    /** «Закончить», or the text is typed to the end. A word still in the IME composition is judged as typed. */
    fun end() {
        if (ended) return
        if (composing.isNotEmpty()) {
            committed += composing
            composing = ""
            result = matcher.update(committed)
            caretUnit = caretOf(result)
            noteCaret()
        }
        endedAt = clock()
        ended = true
    }

    /** Input only goes to the end: the caret is forced there, the IME composition is kept. */
    fun onValue(v: TextFieldValue): TextFieldValue {
        val comp = v.composition
        val c = if (comp != null && !comp.collapsed) v.text.removeRange(comp.min, comp.max) else v.text
        if (ended) return TextFieldValue(committed, TextRange(committed.length))
        composing = if (comp != null && !comp.collapsed) v.text.substring(comp.min, comp.max) else ""
        val before = caretUnit
        if (c != committed) {
            committed = c
            result = matcher.update(c)
            caretUnit = caretOf(result)
            if (result.finished) end()
        }
        noteCaret()
        markSample(minOf(before, caretUnit))
        if (!ended && composing.isNotEmpty() && compositionFinishes()) end()
        return TextFieldValue(v.text, TextRange(v.text.length), comp)
    }

    /** Replaces the committed text at once (a resumed attempt). */
    fun restore(typed: String) {
        onValue(TextFieldValue(typed, TextRange(typed.length)))
    }

    /** The committed text and the composition after it: what is kept when the run is saved. */
    val typedAll: String get() = committed + composing

    /** Everything a recreated screen needs to go on with the same run. */
    fun snapshot() = TypingSnapshot(typedAll, level, elapsedMs, hinted.toSet(), samplePieces.toSet(), sampleShown, ended)

    fun resume(s: TypingSnapshot) {
        level = s.level
        startedAt = clock() - s.elapsedMs
        restore(s.typed)
        // the saved marks replace whatever the fresh session noted on its own
        hinted.clear()
        hinted += s.hinted.filter { it in unitIndex }
        samplePieces.clear()
        samplePieces += s.samplePieces
        sampleShown = s.sampleShown
        if (s.ended) end()
    }

    fun look(pieceId: String, index: Int): WordLook {
        val key = WordKey(pieceId, index)
        val k = unitIndex[key] ?: return WordLook.NONE
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

    /**
     * Pieces still ahead of [from] are typed with the sample once input arrives while it is on; the sample
     * seen before the first keystroke is reading, not typing with it.
     */
    private fun markSample(from: Int) {
        if (level != SampleLevel.SAMPLE || typedAll.isEmpty()) return
        for (k in from until keys.size) samplePieces += keys[k].pieceId
    }

    /** A memory cue counts once its word is reached; typing with the sample marks every piece still ahead. */
    private fun noteCaret() {
        val caret = caretUnit
        if (level == SampleLevel.MEMORY) {
            for (k in minOf(reached, caret)..minOf(caret, keys.size - 1)) {
                if (!empty[k] && WordHistory.countsAsHint(WordHistory.look(level, stats[keys[k]], atCaret = true, passed = false))) hinted += keys[k]
            }
        }
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

/** Pieces a hifz or practice recital logs as errors: mistakes, hints, and typing with the sample (spec §5). */
fun TypedRun.recitalErrors(): Set<String> = errorPieces + hintedPieces + pieces.filterValues { it.withSample }.keys

private const val DRAFT_V2 = "v2"
private const val DRAFT_FIELD = '\u001D'
private const val LIST = '\u001E'
private const val PAIR = '\u001F'

internal fun encodeKeys(k: Set<WordKey>): String = k.joinToString(LIST.toString()) { "${it.pieceId}$PAIR${it.index}" }
internal fun decodeKeys(s: String): Set<WordKey> = if (s.isEmpty()) emptySet()
else s.split(LIST).mapNotNull { e -> e.split(PAIR).takeIf { it.size == 2 }?.let { f -> f[1].toIntOrNull()?.let { WordKey(f[0], it) } } }.toSet()
internal fun encodeIds(ids: Set<String>): String = ids.joinToString(LIST.toString())
internal fun decodeIds(s: String): Set<String> = if (s.isEmpty()) emptySet() else s.split(LIST).toSet()

/**
 * The draft row's `level` column also carries the run's hint marks (no new columns needed):
 * `v2 ␝ LEVEL ␝ sampleShown ␝ sample pieces ␝ hinted words`. A plain level name (older drafts) still reads.
 */
fun encodeDraftLevel(s: TypingSnapshot): String =
    listOf(DRAFT_V2, s.level.name, if (s.sampleShown) "1" else "0", encodeIds(s.samplePieces), encodeKeys(s.hinted))
        .joinToString(DRAFT_FIELD.toString())

fun decodeDraftLevel(code: String, typed: String, elapsedMs: Long): TypingSnapshot {
    val f = code.split(DRAFT_FIELD)
    fun level(name: String) = SampleLevel.entries.firstOrNull { it.name == name } ?: SampleLevel.MEMORY
    if (f.size < 5 || f[0] != DRAFT_V2) return TypingSnapshot(typed, level(code), elapsedMs, emptySet(), emptySet(), false, false)
    return TypingSnapshot(typed, level(f[1]), elapsedMs, decodeKeys(f[4]), decodeIds(f[3]), f[2] == "1", false)
}
