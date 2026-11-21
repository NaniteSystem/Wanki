package app.wanki.ui.review

import app.wanki.ui.Route
import app.wanki.ui.common.rememberSourceOpener
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.AppGraph
import app.wanki.anki.CardBlock
import app.wanki.anki.CardContent
import app.wanki.anki.CardRenderer
import app.wanki.anki.NoteType
import app.wanki.data.AnswerMode
import app.wanki.data.CardWithNote
import app.wanki.data.DeckEntity
import app.wanki.data.DeckTree
import app.wanki.data.NoteEntity
import app.wanki.data.Repository
import app.wanki.graph
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.ToolSelection
import app.wanki.review.AnswerGrader
import app.wanki.review.Grade
import app.wanki.review.WritingLayout
import app.wanki.scoring.FailReason
import app.wanki.scoring.RatingPolicy
import app.wanki.srs.FsrsScheduler
import app.wanki.srs.Rating
import app.wanki.ui.common.CardBlocks
import app.wanki.ui.common.MenuButton
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import app.wanki.writing.Cell
import app.wanki.writing.HintColor
import app.wanki.writing.ReviewColors
import app.wanki.writing.StrokeOrderAnimation
import app.wanki.writing.WritingPaper
import app.wanki.writing.drawReference
import app.wanki.writing.drawReview
import app.wanki.writing.drawStrokeHint
import app.wanki.writing.drawTianzige
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { QUESTION, ANSWER }

/** One study session over a deck. */
class ReviewState(private val graph: AppGraph, private val deckId: String) {
    var deck by mutableStateOf<DeckEntity?>(null)
        private set
    /** Every deck by id: cards of subdecks are answered in their own deck's language. */
    private var decks: Map<String, DeckEntity> = emptyMap()
    private var tree: DeckTree? = null
    private var types: Map<String, NoteType> = mapOf(NoteType.BASIC.id to NoteType.BASIC)
    private val contentCache = HashMap<String, CardContent>()
    val queue = mutableStateListOf<CardWithNote>()
    var loaded by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.QUESTION)
        private set
    var grade by mutableStateOf<Grade?>(null)
        private set
    var grading by mutableStateOf(false)
        private set
    /** 0 = no hint, 1 = the next stroke (per cell), 2 = the whole character. */
    var hintLevel by mutableStateOf(0)
        private set
    val showHint: Boolean get() = hintLevel > 0

    /**
     * First meeting with a new written card (as in Skritter): the character is shown to trace;
     * this does not count as a hint.
     */
    var intro by mutableStateOf(false)
        private set
    var reviewed by mutableStateOf(0)
        private set
    var strokeActive by mutableStateOf(false)
    val layer = InkLayer()
    private var usedHint = false
    private var shownAt = System.currentTimeMillis()
    private var answeredAt = 0L
    private var writingSize = 0f to 0f

    val current: CardWithNote? get() = queue.firstOrNull()
    val scheduler: FsrsScheduler get() = graph.repository.scheduler(graph.settings.desiredRetention.toDouble())

    val mode: AnswerMode
        get() = current?.card?.answerMode?.let { runCatching { AnswerMode.valueOf(it) }.getOrNull() } ?: AnswerMode.CLASSIC

    val noteType: NoteType get() = current?.let { types[it.note.noteTypeId] } ?: NoteType.BASIC

    /** The current card rendered: question and answer sides, and the text written by hand. */
    val content: CardContent?
        get() = current?.let { c ->
            val key = "${c.card.id}|${c.card.writeFieldOrd}|${c.note.updatedAt}|${c.note.fields.hashCode()}"
            contentCache.getOrPut(key) {
                CardRenderer.render(noteType, c.note, c.card.templateOrd, c.card.writeFieldOrd, tree?.path(c.card.deckId).orEmpty())
            }
        }

    /** What is written (and checked, and read aloud). */
    val answer: String get() = content?.writeAnswer.orEmpty()
    val answerHasMask: Boolean get() = content?.answer.orEmpty().any { it is CardBlock.Mask }
    val layout: WritingLayout get() = WritingLayout.forAnswer(answer)
    val language: String get() = current?.let { decks[it.card.deckId]?.language } ?: deck?.language ?: "ja"

    suspend fun load() {
        val tree = graph.repository.deckTree()
        this.tree = tree
        types = graph.repository.noteTypes()
        decks = tree.byId
        deck = decks[deckId]
        queue.clear()
        queue.addAll(graph.repository.reviewQueue(deckId))
        loaded = true
        resetCard()
    }

    private fun resetCard() {
        phase = Phase.QUESTION
        grade = null
        layer.clear()
        usedHint = false
        intro = current?.let { it.card.lastReview == null && mode == AnswerMode.HANDWRITING } == true
        hintLevel = if (intro) 2 else 0
        shownAt = System.currentTimeMillis()
        answeredAt = 0
    }

    /** Hint button: next stroke → whole character → off. */
    fun toggleHint() {
        hintLevel = (hintLevel + 1) % 3
        if (hintLevel > 0 && phase == Phase.QUESTION && !intro) usedHint = true
    }

    suspend fun switchMode(m: AnswerMode) {
        val c = current ?: return
        if (m.name == c.card.answerMode) return
        graph.repository.setAnswerMode(c.card, m)
        queue[0] = c.copy(card = c.card.copy(answerMode = m.name))
        resetCard()
    }

    fun reveal() {
        answeredAt = System.currentTimeMillis()
        phase = Phase.ANSWER
    }

    fun cellsFilled(cellSide: Float): Boolean {
        val l = layout
        if (l !is WritingLayout.Cells) return !layer.isEmpty
        val cells = AnswerGrader.splitIntoCells(layer.strokes.toList(), l.chars.size, cellSide)
        return cells.all { it.isNotEmpty() }
    }

    suspend fun check(cellSide: Float, width: Float, height: Float) {
        if (grading || phase != Phase.QUESTION || layer.isEmpty) return
        val lang = language
        grading = true
        answeredAt = System.currentTimeMillis()
        writingSize = width to height
        val strokes = layer.strokes.toList()
        grade = when (val l = layout) {
            is WritingLayout.Cells -> graph.grader.gradeCells(
                l.chars, AnswerGrader.splitIntoCells(strokes, l.chars.size, cellSide), cellSide, lang,
            )
            WritingLayout.Line -> graph.grader.gradeLine(answer, strokes, width, height, lang)
        }
        grading = false
        phase = Phase.ANSWER
    }

    fun giveUp() {
        answeredAt = System.currentTimeMillis()
        grade = Grade(passed = false, score = 0.0, beauty = null)
        phase = Phase.ANSWER
    }

    /** Suggested rating for a graded written answer: recall decides, handwriting does not. */
    fun suggested(): Rating? {
        val g = grade ?: return null
        val passed = g.passed ?: return null
        val strokeCount = (layout as? WritingLayout.Cells)?.chars?.sumOf { graph.references.get(it, language)?.strokes?.size ?: 3 } ?: 6
        if (intro) return if (passed) Rating.GOOD else Rating.AGAIN
        val r = RatingPolicy().suggest(passed, usedHint, answeredAt - shownAt, strokeCount)
        return Rating.valueOf(r.name)
    }

    fun intervals(): Map<Rating, Long> {
        val c = current ?: return emptyMap()
        return scheduler.preview(graph.repository.srsState(c.card), System.currentTimeMillis(), c.card.seed)
    }

    private var rating = false

    suspend fun rate(r: Rating) {
        if (rating) return
        rating = true
        try {
            rateNow(r)
        } finally {
            rating = false
        }
    }

    private suspend fun rateNow(r: Rating) {
        val c = current ?: return
        val now = System.currentTimeMillis()
        val g = grade
        val written = mode == AnswerMode.HANDWRITING
        val answered = graph.repository.answer(
            card = c.card, rating = r, scheduler = scheduler, mode = mode,
            score = g?.score, beauty = g?.beauty, durationMs = (if (answeredAt > 0) answeredAt else now) - shownAt,
            usedHint = usedHint, ink = if (written) layer.drawing() else null,
            language = language,
            charScores = if (written) g?.cells?.filter { it.result != null }?.map { Repository.CharScore(it.char, it.score, it.result?.beauty?.score) }.orEmpty() else emptyList(),
            now = now,
        )
        val updated = answered.after
        lastAnswer = c to answered
        queue.removeAt(0)
        if (updated.due <= now + Repository.LEARN_AHEAD_MS) queue.add(c.copy(card = updated))
        reviewed++
        resetCard()
    }

    /** The last rated card with its answer, for "undo" (as in Anki). */
    var lastAnswer by mutableStateOf<Pair<CardWithNote, Repository.Answered>?>(null)
        private set

    /** "Not today": the card leaves this session and comes back tomorrow. */
    suspend fun buryCurrent() {
        val c = current ?: return
        graph.repository.buryUntilTomorrow(listOf(c.card.id))
        queue.removeAll { it.card.id == c.card.id }
        resetCard()
    }

    suspend fun suspendCurrent() {
        val c = current ?: return
        graph.repository.setSuspended(listOf(c.card.id), true)
        queue.removeAll { it.card.id == c.card.id }
        resetCard()
    }

    /** Quick edit of the current card's fields; the answer in progress is kept. */
    suspend fun editCurrent(fields: List<String>, tags: String) {
        val c = current ?: return
        graph.repository.saveCard(c, fields, tags, c.card.deckId, AnswerMode.valueOf(c.card.answerMode), c.card.writeFieldOrd)
        graph.repository.card(c.card.id)?.let { fresh -> if (current?.card?.id == fresh.card.id) queue[0] = fresh }
    }

    fun speakAnswer(): Boolean = graph.speaker.speak(answer, language)

    suspend fun undoLast() {
        val (c, a) = lastAnswer ?: return
        lastAnswer = null
        graph.repository.undo(a)
        queue.removeAll { it.card.id == c.card.id }
        queue.add(0, c)
        reviewed = (reviewed - 1).coerceAtLeast(0)
        resetCard()
    }
}

@Composable
fun ReviewScreen(deckId: String, onClose: () -> Unit, onOpenSource: (Route) -> Unit = {}) {
    val graph = LocalContext.current.graph
    val state = remember(deckId) { ReviewState(graph, deckId) }
    val scope = rememberUiScope()
    LaunchedEffect(deckId) { ui { state.load() } }

    val context = LocalContext.current
    val source = rememberSourceOpener(state.current?.note?.id, onOpenSource)
    var editing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.loaded) { runCatching { focus.requestFocus() } }
    // card sounds play by themselves (as in Anki); the answer is read aloud when it appears (setting)
    LaunchedEffect(state.current?.card?.id, state.phase) {
        val side = if (state.phase == Phase.QUESTION) state.content?.question else state.content?.answer
        val sound = side?.firstOrNull { it is CardBlock.Sound } as? CardBlock.Sound
        if (sound != null) graph.media.play(sound.file)
        else if (state.phase == Phase.ANSWER && graph.settings.autoSpeak) state.speakAnswer()
    }
    fun speak() {
        if (!state.speakAnswer()) Toast.makeText(context, "Нет голоса для этого языка: установите его в настройках синтеза речи", Toast.LENGTH_LONG).show()
    }

    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || state.current == null) return@onPreviewKeyEvent false
            reviewKey(e.key, state, scope)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            val modeChips: @Composable () -> Unit = {
                if (state.current != null) {
                    FilterChip(state.mode == AnswerMode.CLASSIC, { scope.launch { state.switchMode(AnswerMode.CLASSIC) } }, { Text("Классика") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(state.mode == AnswerMode.HANDWRITING, { scope.launch { state.switchMode(AnswerMode.HANDWRITING) } }, { Text("Письмо") })
                }
            }
            val undo: @Composable () -> Unit = {
                if (state.lastAnswer != null) {
                    TextButton(onClick = { scope.launch { state.undoLast() } }) { Text("↶ Отменить оценку", maxLines = 1) }
                }
            }
            val menu: @Composable () -> Unit = {
                if (state.current != null) {
                    MenuButton(
                        "⋮",
                        listOf<Pair<String, () -> Unit>>(
                            "Изменить карточку" to { editing = true },
                            "Озвучить ответ" to ::speak,
                            "Отложить до завтра" to { scope.launch { state.buryCurrent() } },
                            "Приостановить карточку" to { scope.launch { state.suspendCurrent() } },
                        ) + listOfNotNull(source.target?.let { source.label to { source.open() } }),
                        enabled = { it != source.label || source.enabled },
                    )
                }
            }
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("‹ Закрыть") }
                    Column(Modifier.weight(1f)) {
                        Text(state.deck?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "осталось ${state.queue.size} · повторено ${state.reviewed}",
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (wide) {
                        undo()
                        modeChips()
                    }
                    menu()
                }
                if (!wide && state.current != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        modeChips()
                        Spacer(Modifier.weight(1f))
                        undo()
                    }
                }
            }
        }
        val card = state.current
        when {
            !state.loaded -> Unit
            card == null -> Finished(state.reviewed, onClose)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // a tape card's answer is the same picture opened: it replaces the closed one
                if (!(state.phase == Phase.ANSWER && state.answerHasMask)) CardBlocks(state.content?.question.orEmpty())
                if (state.mode == AnswerMode.HANDWRITING) WritingAnswer(state) else ClassicAnswer(state)
                if (state.phase == Phase.ANSWER) RatingRow(state)
            }
        }
    }
    if (editing) {
        state.current?.let { c ->
            QuickEditDialog(c, state.noteType, onDismiss = { editing = false }) { fields, tags ->
                editing = false
                scope.launch { state.editCurrent(fields, tags) }
            }
        }
    }
}

/** Hardware keyboard: Space/Enter shows the answer or takes the suggested rating, 1–4 rate, Z undoes. */
private fun reviewKey(key: Key, state: ReviewState, scope: CoroutineScope): Boolean {
    val rating = when (key) {
        Key.One, Key.NumPad1 -> Rating.AGAIN
        Key.Two, Key.NumPad2 -> Rating.HARD
        Key.Three, Key.NumPad3 -> Rating.GOOD
        Key.Four, Key.NumPad4 -> Rating.EASY
        else -> null
    }
    return when {
        rating != null && state.phase == Phase.ANSWER -> { scope.launch { state.rate(rating) }; true }
        (key == Key.Spacebar || key == Key.Enter) && state.phase == Phase.QUESTION && state.mode == AnswerMode.CLASSIC -> { state.reveal(); true }
        (key == Key.Spacebar || key == Key.Enter) && state.phase == Phase.ANSWER -> {
            val r = state.suggested() ?: Rating.GOOD
            scope.launch { state.rate(r) }
            true
        }
        key == Key.Z && state.lastAnswer != null -> { scope.launch { state.undoLast() }; true }
        else -> false
    }
}

@Composable
private fun QuickEditDialog(c: CardWithNote, type: NoteType, onDismiss: () -> Unit, onSave: (List<String>, String) -> Unit) {
    val values = remember(c.card.id) {
        val v = c.note.fields.split(NoteEntity.FIELD_SEPARATOR)
        mutableStateListOf(*Array(maxOf(v.size, type.fields.size)) { v.getOrElse(it) { "" } })
    }
    var tags by remember(c.card.id) { mutableStateOf(c.note.tags) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить карточку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values.indices.forEach { i ->
                    OutlinedTextField(values[i], { values[i] = it }, label = { Text(type.fields.getOrElse(i) { "Поле ${i + 1}" }) })
                }
                OutlinedTextField(tags, { tags = it }, label = { Text("Теги") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = values.any { it.isNotBlank() }, onClick = { onSave(values.toList(), tags) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ClassicAnswer(state: ReviewState) {
    if (state.phase == Phase.QUESTION) {
        Button(onClick = state::reveal, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Показать ответ") }
    } else {
        if (state.noteType.isBasic && !state.answerHasMask) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.answer, fontSize = 40.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                SpeakButton(state)
            }
        } else {
            CardBlocks(state.content?.answer.orEmpty(), textSize = 28.sp)
            if (state.answer.isNotBlank()) SpeakButton(state)
        }
    }
}

@Composable
private fun SpeakButton(state: ReviewState) {
    val context = LocalContext.current
    TextButton(onClick = {
        if (!state.speakAnswer()) Toast.makeText(context, "Нет голоса для этого языка", Toast.LENGTH_SHORT).show()
    }) { Text("🔊") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WritingAnswer(state: ReviewState) {
    val graph = LocalContext.current.graph
    val scope = rememberUiScope()
    val density = LocalDensity.current
    val layout = state.layout
    val cellsLayout = layout as? WritingLayout.Cells
    val tool = remember { ToolSelection(size = 6f) }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val n = cellsLayout?.chars?.size ?: 1
        val cellDp = if (cellsLayout != null) minOf(maxWidth / n, 260.dp) else 0.dp
        val widthDp = if (cellsLayout != null) cellDp * n else maxWidth
        val heightDp = if (cellsLayout != null) cellDp else 200.dp
        val cellPx = with(density) { cellDp.toPx() }
        val widthPx = with(density) { widthDp.toPx() }
        val heightPx = with(density) { heightDp.toPx() }
        val brush = remember(cellPx) { tool.copy(size = if (cellPx > 0) cellPx / 30f else 6f * density.density) }

        // Automatic check: once every cell has ink and the pen has rested for a moment.
        LaunchedEffect(state.layer.version, state.strokeActive, state.phase) {
            if (state.phase != Phase.QUESTION || state.strokeActive || state.layer.isEmpty) return@LaunchedEffect
            val ready = state.cellsFilled(cellPx)
            val base = graph.settings.autoCheckDelayMs.toLong()
            delay(if (ready) base else base * 3)
            if (cellsLayout == null || state.cellsFilled(cellPx)) ui { state.check(cellPx, widthPx, heightPx) }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.intro && state.phase == Phase.QUESTION) {
                val refs = cellsLayout?.chars?.mapNotNull { graph.references.get(it, state.language) }.orEmpty()
                Text("Новая карточка: посмотрите порядок черт и обведите по образцу", style = MaterialTheme.typography.titleSmall, color = HintColor)
                if (refs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { refs.forEach { StrokeOrderAnimation(it, Modifier.size(96.dp)) } }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = WritingPaper, modifier = Modifier.size(widthDp, heightDp).testTag("writing-canvas")) {
                Box {
                    InkSurface(
                        layer = state.layer,
                        tool = brush,
                        enabled = state.phase == Phase.QUESTION && !state.grading,
                        showInk = state.phase == Phase.QUESTION || state.grade?.cells?.any { it.result != null } != true,
                        onStrokeStarted = { state.strokeActive = true },
                        onStrokeFinished = { state.strokeActive = false },
                        modifier = Modifier.fillMaxSize(),
                        background = {
                            if (cellsLayout != null) {
                                cellsLayout.chars.forEachIndexed { i, ch ->
                                    val cell = Cell(i * cellPx, 0f, cellPx)
                                    drawTianzige(cell)
                                    if (state.phase == Phase.QUESTION) graph.references.get(ch, state.language)?.let { ref ->
                                        when (state.hintLevel) {
                                            2 -> drawReference(ref, cell)
                                            1 -> {
                                                // next stroke of this cell: as many strokes as already written in it
                                                val written = AnswerGrader.splitIntoCells(state.layer.strokes.toList(), cellsLayout.chars.size, cellPx)[i].size
                                                drawStrokeHint(ref, written, cell)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val y = size.height * 0.72f
                                drawLine(ReviewColors.grid, androidx.compose.ui.geometry.Offset(16f, y), androidx.compose.ui.geometry.Offset(size.width - 16f, y), 2f)
                            }
                        },
                        overlay = {
                            val g = state.grade
                            if (state.phase == Phase.ANSWER && g != null && cellsLayout != null) {
                                val split = AnswerGrader.splitIntoCells(state.layer.strokes.toList(), cellsLayout.chars.size, cellPx)
                                g.cells.forEachIndexed { i, cg ->
                                    val ref = graph.references.get(cg.char, state.language) ?: return@forEachIndexed
                                    val r = cg.result ?: return@forEachIndexed
                                    drawReview(ref, r, split[i], Cell(i * cellPx, 0f, cellPx))
                                }
                            }
                        },
                    )
                    if (state.showHint && cellsLayout == null && state.phase == Phase.QUESTION) {
                        Text(state.answer, fontSize = 48.sp, color = Color(0x33808080), modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            if (state.phase == Phase.QUESTION) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        state.showHint, state::toggleHint,
                        { Text(when (state.hintLevel) { 0 -> "Подсказка"; 1 -> "Весь знак"; else -> "Скрыть" }) },
                    )
                    OutlinedButton(onClick = { state.layer.clear() }, enabled = !state.layer.isEmpty) { Text("Стереть") }
                    OutlinedButton(onClick = state::giveUp) { Text("Не помню") }
                    TextButton(
                        onClick = { scope.launch { state.check(cellPx, widthPx, heightPx) } },
                        enabled = !state.layer.isEmpty && !state.grading,
                    ) { Text(if (state.grading) "Проверка…" else "Проверить") }
                }
                Text(
                    "Проверка запустится сама, когда вы закончите писать",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Verdict(state)
            }
        }
    }
}

@Composable
private fun Verdict(state: ReviewState) {
    val g = state.grade
    val graph = LocalContext.current.graph
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.answer, fontSize = 40.sp, fontWeight = FontWeight.Medium)
            SpeakButton(state)
        }
        // the rest of an Anki answer side (readings, examples, pictures)
        if (!state.noteType.isBasic) CardBlocks(state.content?.answer.orEmpty(), textSize = 22.sp)
        // stroke order of the answer, animated (tap to replay)
        val refs = (state.layout as? WritingLayout.Cells)?.chars?.mapNotNull { graph.references.get(it, state.language) }.orEmpty()
        if (refs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                refs.forEach { StrokeOrderAnimation(it, Modifier.size(96.dp)) }
            }
        }
        when (g?.passed) {
            true -> Text(
                "Верно" + (g.score?.let { " · %.0f%%".format(it) } ?: "") + (g.beauty?.let { " · почерк %.0f".format(it) } ?: ""),
                color = ReviewColors.correct, style = MaterialTheme.typography.titleMedium,
            )
            false -> Text("Неверно" + (g.score?.takeIf { it > 0 }?.let { " · %.0f%%".format(it) } ?: ""), color = ReviewColors.wrong, style = MaterialTheme.typography.titleMedium)
            null -> Text("Не удалось проверить автоматически — оцените себя", style = MaterialTheme.typography.bodyMedium)
        }
        g?.cells?.forEach { c ->
            val reasons = c.result?.failReasons.orEmpty().map { reasonText(it, c.result?.confusedWith, c.result?.missingRefStrokes?.size ?: 0) }
            val line = buildString {
                append(c.char)
                append(": ")
                append(when (c.passed) { true -> "верно"; false -> "неверно"; null -> "?" })
                c.score?.let { append(" %.0f%%".format(it)) }
                if (reasons.isNotEmpty()) append(" — ").append(reasons.joinToString(", "))
                if (c.result?.acceptedByRecognizer == true && c.result?.failReasons?.isEmpty() == true) append(" · форма необычная, но знак узнаётся")
                if (c.result?.needsHandwritingPractice == true) append(" · почерк в тренировку")
                if (c.result == null && c.candidates != null) append(" (распознано: ${c.candidates.take(3).joinToString(" ")})")
            }
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        g?.recognized?.let { Text("Распознано: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun reasonText(r: FailReason, confused: String?, missing: Int) = when (r) {
    FailReason.MISSING_STROKES -> "пропущено черт: $missing"
    FailReason.EXTRA_STROKES -> "лишние черты"
    FailReason.NOT_RECOGNIZED -> "не распознан"
    FailReason.CONFUSED_WITH_LOOKALIKE -> "похоже на «$confused»"
    FailReason.LOW_SCORE -> "форма далека от правильной"
}

/** After a correct written answer the suggested rating is applied by itself after this pause. */
private const val AUTO_ADVANCE_MS = 2200

@Composable
private fun RatingRow(state: ReviewState) {
    val scope = rememberUiScope()
    val cardId = state.current?.card?.id
    val intervals = remember(cardId, state.phase) { state.intervals() }
    val suggested = state.suggested()
    // Correct answers move on by themselves; mistakes wait so the review can be studied.
    val autoRating = if (state.grade?.passed == true) suggested else null
    var paused by remember(cardId, state.phase) { mutableStateOf(false) }
    val progress = remember(cardId, state.phase) { Animatable(0f) }
    LaunchedEffect(autoRating, paused, cardId) {
        if (autoRating == null || paused) return@LaunchedEffect
        progress.animateTo(1f, tween(AUTO_ADVANCE_MS, easing = LinearEasing))
        ui { state.rate(autoRating) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Rating.entries.forEach { r ->
            val colors = if (r == suggested) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
            Button(
                onClick = { scope.launch { state.rate(r) } }, colors = colors,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r.label(), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    Text(formatInterval(intervals[r] ?: 0), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (autoRating != null && !paused) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Дальше с оценкой «${autoRating.label()}» — или выберите другую",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { paused = true }) { Text("Пауза") }
            }
        }
    } else if (suggested != null) {
        Text("Подсвечена оценка по результату проверки", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Rating.label() = when (this) {
    Rating.AGAIN -> "Снова"
    Rating.HARD -> "Трудно"
    Rating.GOOD -> "Хорошо"
    Rating.EASY -> "Легко"
}

private fun formatInterval(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "${min.coerceAtLeast(1)} мин"
        min < 60 * 24 -> "${min / 60} ч"
        min < 60 * 24 * 30 -> "${min / (60 * 24)} д"
        min < 60 * 24 * 365 -> "%.1f мес".format(min / (60.0 * 24 * 30))
        else -> "%.1f г".format(min / (60.0 * 24 * 365))
    }
}

@Composable
private fun Finished(reviewed: Int, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("На сегодня всё", style = MaterialTheme.typography.headlineMedium)
        Text("Повторено карточек: $reviewed", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose) { Text("К колодам") }
    }
}
