package app.wanki.ui.texts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.AppGraph
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.graph
import app.wanki.texts.Chain
import app.wanki.texts.ChainStep
import app.wanki.texts.FirstLetters
import app.wanki.texts.RecallOutcome
import app.wanki.texts.TextRating
import app.wanki.texts.VanishingCues
import app.wanki.texts.Word
import app.wanki.texts.WordBreaker
import app.wanki.texts.WordDisplay
import app.wanki.texts.Words
import app.wanki.texts.TextWords
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.launch

/** One study session over a request's pieces. Session state lives here; leaving mid-session saves nothing. */
class StudyState(private val graph: AppGraph, val request: StudyRequest) {
    var loaded by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var pieces by mutableStateOf<List<TextPieceEntity>>(emptyList())
        private set
    private var textsById: Map<String, TextEntity> = emptyMap()
    private var allPieces: List<TextPieceEntity> = emptyList()
    private val breakers = HashMap<String, WordBreaker>()
    private val wordCache = HashMap<String, List<Word>>()
    private val startedAt = System.currentTimeMillis()

    var mode by mutableStateOf(if (request.kind == StudyKind.LEARN) StudyMode.READ else StudyMode.RECITE)
        private set

    // reveals and mistakes (persist across mode switches; "used hint ⇒ not clean")
    var revealedWords by mutableStateOf<Set<WordRef>>(emptySet())
        private set
    var errorPieces by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Pieces a hint was taken in; mode switches and taps on the card never clear it (only a fresh attempt does). */
    var hintedPieces by mutableStateOf<Set<String>>(emptySet())
        private set
    var hints by mutableIntStateOf(0)
        private set
    var allRevealed by mutableStateOf(false)
        private set

    // cues
    var cueLevel by mutableIntStateOf(0)
        private set
    /** True once a word was revealed on the current level: "Дальше" then retries it instead of rising. */
    private var cueDirty by mutableStateOf(false)

    // chain
    var chainIndex by mutableIntStateOf(0)
        private set
    val chainSteps: List<ChainStep> get() = Chain.steps(pieces.size)

    // review (one due piece at a time)
    var reviewIndex by mutableIntStateOf(0)
        private set
    private var pieceShownAt = startedAt

    /** True while a result is being written: «Без ошибок», «Готово» and the ratings are disabled, double taps dropped. */
    private val saving = BusyGuard()
    val busy: Boolean get() = saving.busy

    // first-letters typing
    private var flIndex by mutableIntStateOf(0)
    val flTargets: List<WordRef> by lazy {
        pieces.flatMap { p -> wordsOf(p).indices.map { WordRef(p.id, it) } }
    }

    suspend fun load() {
        pieces = graph.texts.piecesByIds(request.pieceIds)
        textsById = pieces.map { it.textId }.distinct().mapNotNull { id -> graph.texts.text(id)?.let { id to it } }.toMap()
        // review cues are the preceding piece of the text, which may not itself be due
        allPieces = request.textId?.let { graph.texts.pieces(it) } ?: pieces
        loaded = true
    }

    fun language(p: TextPieceEntity): String = textsById[p.textId]?.language ?: "ru"
    fun title(textId: String): String = textsById[textId]?.title.orEmpty()
    private fun breaker(lang: String) = breakers.getOrPut(lang) { TextWords.breaker(lang) }
    fun wordsOf(p: TextPieceEntity): List<Word> = wordCache.getOrPut(p.id) { breaker(language(p)).words(p.content) }
    fun revealedOf(pieceId: String): Set<Int> = revealedWords.filter { it.pieceId == pieceId }.mapTo(HashSet()) { it.index }

    val clean: Boolean get() = isClean(hints, errorPieces)

    fun switchMode(m: StudyMode) {
        if (m == mode) return
        // LEARN judges the recital itself: practice hints in other modes must not block «Без ошибок»
        if (startsFreshRecital(request.kind, mode, m)) resetAttempt()
        mode = m
        // a fresh view for the new mode; hints and marked mistakes carry over
        revealedWords = emptySet()
        allRevealed = false
        // the cue level is local to CUES: READ/CHAIN/RECITE must not inherit a stale level
        cueLevel = 0
        cueDirty = false
    }

    /** Reveals a word; a [hint] reveal (a tap because you forgot) counts against a clean recital. */
    fun revealWord(piece: TextPieceEntity, index: Int, hint: Boolean = true) {
        val ref = WordRef(piece.id, index)
        if (ref in revealedWords) return
        revealedWords = revealedWords + ref
        if (hint) {
            hints++
            cueDirty = true
            hintedPieces = hintedPieces + piece.id
        }
    }

    /** The slider: only a real level change resets the reveals (a drag fires many events on the same level). */
    fun jumpCue(level: Int) {
        cueLevel = cueJump(cueLevel, level) ?: return
        revealedWords = emptySet()
        cueDirty = false
    }

    /**
     * "Дальше" = "I recited this level": a clean level rises to the next one; a dirty one is retried with
     * its reveals cleared. The session's hint count stays, so "used hint ⇒ not clean" still holds.
     */
    fun cueNext() {
        cueLevel = nextCueLevel(cueLevel, revealedThisLevel = cueDirty)
        revealedWords = emptySet()
        cueDirty = false
    }

    fun reveal() { allRevealed = true }

    /** Shown as an error: marked by hand or a hint was taken in it. */
    fun isError(pieceId: String): Boolean = pieceId in errorPieces || pieceId in hintedPieces

    fun toggleError(pieceId: String) {
        errorPieces = toggleMark(errorPieces, hintedPieces, pieceId)
    }

    // --- chain ---
    val chainStep: ChainStep? get() = chainSteps.getOrNull(chainIndex)
    val chainAtEnd: Boolean get() = chainIndex >= chainSteps.lastIndex
    fun chainNext() { if (!chainAtEnd) { chainIndex++; allRevealed = false } }

    // --- first letters ---
    val flTarget: WordRef? get() = flTargets.getOrNull(flIndex)
    fun flType(typed: String) {
        val target = flTarget ?: return
        val piece = pieces.firstOrNull { it.id == target.pieceId } ?: return
        val word = wordsOf(piece).getOrNull(target.index)?.let { piece.content.substring(it.start, it.end) } ?: return
        val ok = FirstLetters.matches(typed, word)
        revealWord(piece, target.index, hint = !ok)
        flIndex++
    }

    // --- finishing ---
    private fun duration(): Long = (System.currentTimeMillis() - pieceShownAt).coerceAtLeast(0)
    private fun sessionDuration(): Long = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)

    /** "Без ошибок": the portion is learned. Only valid for a clean recital. */
    suspend fun learnClean() = saving.run {
        if (!clean || finished) return@run
        graph.texts.completePortion(request.textId!!, request.pieceIds, sessionDuration())
        finished = true
    }

    /** "Ещё раз": a fresh attempt in the same LEARN session. */
    fun again() {
        resetAttempt()
        cueLevel = 0
        mode = StudyMode.RECITE
    }

    private fun resetAttempt() {
        revealedWords = emptySet()
        errorPieces = emptySet()
        hintedPieces = emptySet()
        hints = 0
        allRevealed = false
        cueDirty = false
    }

    /** One FSRS review outcome for the current piece, then move on. */
    suspend fun rate(outcome: RecallOutcome) = saving.run {
        if (finished) return@run
        val piece = pieces.getOrNull(reviewIndex) ?: return@run
        val rating = TextRating.rating(outcome, usedHints = hints > 0)
        graph.texts.reviewPiece(piece.id, rating, hints, duration())
        reviewIndex++
        hints = 0
        hintedPieces = emptySet()
        revealedWords = emptySet()
        allRevealed = false
        pieceShownAt = System.currentTimeMillis()
        if (reviewIndex >= pieces.size) finished = true
    }

    /** The preceding piece of the text, shown as the chain cue in a review. */
    fun reviewCue(piece: TextPieceEntity): TextPieceEntity? =
        allPieces.firstOrNull { it.textId == piece.textId && it.pieceIndex == piece.pieceIndex - 1 }

    /** "Готово": write the sabqi/manzil/practice result. */
    suspend fun reciteDone() = saving.run {
        if (finished) return@run
        val errs = recitalErrors(errorPieces, hintedPieces)
        when (request.kind) {
            StudyKind.SABQI -> graph.texts.completeSabqi(request.textId!!, request.pieceIds, errs, sessionDuration())
            StudyKind.MANZIL -> graph.texts.completeManzil(request.pieceIds, errs, sessionDuration())
            StudyKind.PRACTICE -> graph.texts.logPractice(request.textId!!, request.pieceIds, errs, hints, sessionDuration())
            else -> {}
        }
        finished = true
    }

    fun speak(piece: TextPieceEntity): Boolean = graph.speaker.speak(piece.content, language(piece))
}

@Composable
fun StudyScreen(request: StudyRequest, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val state = remember(request) { StudyState(graph, request) }
    val scope = rememberUiScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(request) { ui { state.load() } }
    LaunchedEffect(state.finished) { if (state.finished) onClose() }

    val speak: (TextPieceEntity) -> Unit = { p ->
        graph.media.stop() // your own recording and TTS never talk over each other
        if (!state.speak(p)) { scope.launch { snackbar.showSnackbar("Нет голоса для этого языка") } }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            ScreenHeader(studyTitle(state), onBack = onClose, subtitle = studySubtitle(request.kind), subtitleMaxLines = 1)
            if (!state.loaded) return@Column
            if (state.pieces.isEmpty()) {
                Text("Нет кусков для занятия", Modifier.padding(16.dp))
                return@Column
            }
            if (request.kind == StudyKind.LEARN) ModeBar(state, StudyMode.entries)
            else if (request.kind != StudyKind.REVIEW) ModeBar(state, listOf(StudyMode.RECITE, StudyMode.CUES))
            // your own voice: the current piece in a review, otherwise the whole portion/block
            val voiceIds = if (request.kind == StudyKind.REVIEW) listOfNotNull(state.pieces.getOrNull(state.reviewIndex)?.id)
            else state.pieces.map { it.id }
            if (voiceIds.isNotEmpty()) key(voiceIds) { VoiceBar(voiceIds, snackbar, scope) }

            when (request.kind) {
                StudyKind.REVIEW -> ReviewBody(state, scope, speak)
                StudyKind.LEARN -> when (state.mode) {
                    StudyMode.READ -> ReadBody(state, speak)
                    StudyMode.CUES -> CuesBody(state, speak)
                    StudyMode.FIRST_LETTERS -> FirstLettersBody(state, speak)
                    StudyMode.CHAIN -> ChainBody(state, speak)
                    StudyMode.RECITE -> ReciteBody(state, scope, speak, learn = true)
                }
                else -> when (state.mode) {
                    StudyMode.CUES -> CuesBody(state, speak)
                    else -> ReciteBody(state, scope, speak, learn = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeBar(state: StudyState, modes: List<StudyMode>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modes.forEach { m ->
            FilterChip(
                selected = state.mode == m,
                onClick = { state.switchMode(m) },
                label = { Text(modeLabel(m)) },
                modifier = Modifier.testTag("mode-${m.name}"),
            )
        }
    }
}

/** Scrollable list of pieces with a reusable renderer and an optional footer of action buttons. */
@Composable
private fun PieceList(
    state: StudyState,
    pieces: List<TextPieceEntity>,
    display: (TextPieceEntity) -> List<WordDisplay>,
    allRevealed: Boolean,
    markable: Boolean,
    speak: (TextPieceEntity) -> Unit,
    titled: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var lastText: String? = null
        pieces.forEach { p ->
            if (titled && p.textId != lastText) {
                lastText = p.textId
                // key on the run's first piece, not the text: a circular manzil chunk can show one text
                // as two non-consecutive runs, and a per-text key would collide and crash
                item(key = "title-${p.id}") {
                    Text(state.title(p.textId), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = p.id) { PieceCard(state, p, display(p), allRevealed, markable, speak) }
        }
        item(key = "footer") { Column(Modifier.padding(top = 12.dp)) { footer() } }
    }
}

@Composable
private fun PieceCard(
    state: StudyState,
    piece: TextPieceEntity,
    display: List<WordDisplay>,
    allRevealed: Boolean,
    markable: Boolean,
    speak: (TextPieceEntity) -> Unit,
) {
    val error = state.isError(piece.id)
    val colors = if (error) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    val mod = Modifier.fillMaxWidth().let { if (markable) it.clickable { state.toggleError(piece.id) } else it }
    Card(colors = colors, modifier = mod) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${piece.pieceIndex + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                if (markable && error) Text("ошибка", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                // no TTS while the mic is open: it would end up in the recording
                val recording by LocalContext.current.graph.recorder.active.collectAsState()
                IconButton(onClick = { speak(piece) }, enabled = !recording) { Text("🔊") }
            }
            PieceWords(piece, state.language(piece), state.wordsOf(piece), display, state.revealedOf(piece.id), allRevealed, pieceStyle(state.language(piece))) {
                state.revealWord(piece, it)
            }
            if (piece.note.isNotBlank()) {
                Text(piece.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReadBody(state: StudyState, speak: (TextPieceEntity) -> Unit) {
    PieceList(state, state.pieces, display = { VanishingCues.display(0, state.wordsOf(it).size, it.seed) }, allRevealed = true, markable = false, speak = speak) {
        Text("Прочитайте и послушайте, затем переходите к подсказкам.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CuesBody(state: StudyState, speak: (TextPieceEntity) -> Unit) {
    PieceList(
        state, state.pieces,
        display = { cueDisplay(it, state.wordsOf(it).size, state.cueLevel, hidden = false) },
        allRevealed = false, markable = false, speak = speak, titled = state.request.kind == StudyKind.MANZIL,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Уровень ${state.cueLevel} из ${VanishingCues.MAX_LEVEL}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.cueLevel.toFloat(),
                onValueChange = { state.jumpCue(it.toInt()) },
                valueRange = 0f..VanishingCues.MAX_LEVEL.toFloat(),
                steps = VanishingCues.MAX_LEVEL - 1,
            )
            Text(
                "Тапните пропуск, чтобы открыть слово — это подсказка. Пройдите уровень без подсказок, и откроется следующий.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { state.cueNext() }, modifier = Modifier.testTag("cue-next")) { Text("Дальше") }
        }
    }
}

@Composable
private fun FirstLettersBody(state: StudyState, speak: (TextPieceEntity) -> Unit) {
    val typing = !Words.needsDictionary(state.language(state.pieces.first()))
    var field by remember { mutableStateOf("") }
    PieceList(
        state, state.pieces,
        display = { VanishingCues.display(4, state.wordsOf(it).size, it.seed) },
        allRevealed = false, markable = false, speak = speak,
    ) {
        if (typing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Вводите первую букву каждого слова по порядку — проверю без регистра и огласовок.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = field,
                    onValueChange = { v ->
                        val ch = v.trim()
                        if (ch.isNotEmpty() && state.flTarget != null) { state.flType(ch); field = "" } else field = v
                    },
                    label = { Text("Первая буква") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text("Вспоминайте слова по первым буквам; тапните, чтобы открыть.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChainBody(state: StudyState, speak: (TextPieceEntity) -> Unit) {
    val step = state.chainStep
    if (step == null) { Text("Нет шагов цепочки", Modifier.padding(16.dp)); return }
    val shown = step.targets.toList()
    val cue = step.cue
    val visible = buildList { cue?.let { add(it) }; addAll(shown) }.sorted()
    PieceList(
        state,
        visible.mapNotNull { state.pieces.getOrNull(it) },
        display = { p ->
            val i = state.pieces.indexOf(p)
            val hidden = i in shown && !state.allRevealed
            // chain does its own cue/target hiding: the visible cue line is fully shown (level 0), not a cue level
            cueDisplay(p, state.wordsOf(p).size, 0, hidden = hidden)
        },
        allRevealed = false, markable = false, speak = speak,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Цепочка: вспомните скрытые строки по предыдущей.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.allRevealed) OutlinedButton(onClick = { state.reveal() }, modifier = Modifier.testTag("reveal")) { Text("Показать") }
                else Button(onClick = { state.chainNext() }, enabled = !state.chainAtEnd, modifier = Modifier.testTag("chain-next")) { Text("Дальше") }
            }
            if (state.chainAtEnd && state.allRevealed) {
                Text("Строки собраны. Переключитесь на «Рассказ», чтобы завершить порцию.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReciteBody(state: StudyState, scope: kotlinx.coroutines.CoroutineScope, speak: (TextPieceEntity) -> Unit, learn: Boolean) {
    PieceList(
        state, state.pieces,
        display = { cueDisplay(it, state.wordsOf(it).size, state.cueLevel, hidden = !state.allRevealed) },
        allRevealed = state.allRevealed, markable = state.allRevealed, speak = speak,
        titled = state.request.kind == StudyKind.MANZIL,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.allRevealed) {
                Text("Расскажите всё наизусть, затем откройте и отметьте куски с ошибками.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { state.reveal() }, modifier = Modifier.testTag("reveal")) { Text("Показать") }
            } else if (learn) {
                Text("Отметьте куски, где ошиблись. Без ошибок — порция выучена.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { state.learnClean() } }, enabled = state.clean && !state.busy, modifier = Modifier.testTag("learned")) { Text("Без ошибок") }
                    OutlinedButton(onClick = { state.again() }, enabled = !state.busy) { Text("Ещё раз") }
                }
            } else {
                Text("Отметьте куски, где ошиблись, затем «Готово».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { scope.launch { state.reciteDone() } }, enabled = !state.busy, modifier = Modifier.testTag("done")) { Text("Готово") }
            }
        }
    }
}

@Composable
private fun ReviewBody(state: StudyState, scope: kotlinx.coroutines.CoroutineScope, speak: (TextPieceEntity) -> Unit) {
    val piece = state.pieces.getOrNull(state.reviewIndex) ?: return
    val cue = state.reviewCue(piece)
    val visible = listOfNotNull(cue, piece)
    PieceList(
        state, visible,
        display = { p ->
            if (p.id == piece.id && !state.allRevealed) cueDisplay(p, state.wordsOf(p).size, 0, hidden = true)
            else VanishingCues.display(0, state.wordsOf(p).size, p.seed)
        },
        allRevealed = false, markable = false, speak = speak,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Повторение ${state.reviewIndex + 1} из ${state.pieces.size}", style = MaterialTheme.typography.labelLarge)
            if (!state.allRevealed) {
                Text("Вспомните скрытый кусок по предыдущему; тапом можно подсмотреть слово.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { state.reveal() }, modifier = Modifier.testTag("reveal")) { Text("Показать") }
            } else {
                FlowRowButtons(
                    enabled = !state.busy,
                    "Не вспомнил" to { scope.launch { state.rate(RecallOutcome.FORGOT) } },
                    "С ошибками" to { scope.launch { state.rate(RecallOutcome.ERRORS) } },
                    "Верно" to { scope.launch { state.rate(RecallOutcome.CORRECT) } },
                    "Легко" to { scope.launch { state.rate(RecallOutcome.EASY) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(enabled: Boolean, vararg actions: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { (label, action) -> OutlinedButton(onClick = action, enabled = enabled) { Text(label) } }
    }
}

private fun modeLabel(m: StudyMode) = when (m) {
    StudyMode.READ -> "Чтение"
    StudyMode.CUES -> "Подсказки"
    StudyMode.FIRST_LETTERS -> "Первые буквы"
    StudyMode.CHAIN -> "Цепочка"
    StudyMode.RECITE -> "Рассказ"
}

private fun studyTitle(state: StudyState): String = when (state.request.kind) {
    StudyKind.MANZIL -> "Манзиль"
    else -> state.request.textId?.let { state.title(it) }.orEmpty()
}

private fun studySubtitle(kind: StudyKind) = when (kind) {
    StudyKind.LEARN -> "Учить порцию"
    StudyKind.REVIEW -> "Повторение"
    StudyKind.SABQI -> "Сабки"
    StudyKind.MANZIL -> "Манзиль"
    StudyKind.PRACTICE -> "Рассказать весь текст"
}
