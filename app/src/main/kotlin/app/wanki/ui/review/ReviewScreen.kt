package app.wanki.ui.review

import app.wanki.ui.common.ui
import app.wanki.ui.common.rememberUiScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.AppGraph
import app.wanki.data.AnswerMode
import app.wanki.data.CardWithNote
import app.wanki.data.DeckEntity
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
import app.wanki.writing.Cell
import app.wanki.writing.ReviewColors
import app.wanki.writing.drawReference
import app.wanki.writing.drawReview
import app.wanki.writing.drawTianzige
import app.wanki.writing.HintColor
import app.wanki.writing.StrokeOrderAnimation
import app.wanki.writing.drawStrokeHint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { QUESTION, ANSWER }

/** One study session over a deck. */
class ReviewState(private val graph: AppGraph, private val deckId: String) {
    var deck by mutableStateOf<DeckEntity?>(null)
        private set
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

    val answer: String get() = current?.let { it.note.field(it.card.writeFieldOrd) }.orEmpty()
    val question: String get() = current?.let { it.note.field(if (it.card.writeFieldOrd == 0) 1 else 0) }.orEmpty()
    val layout: WritingLayout get() = WritingLayout.forAnswer(answer)

    suspend fun load() {
        deck = graph.repository.deck(deckId)
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
        val deck = deck ?: return
        grading = true
        answeredAt = System.currentTimeMillis()
        writingSize = width to height
        val strokes = layer.strokes.toList()
        grade = when (val l = layout) {
            is WritingLayout.Cells -> graph.grader.gradeCells(
                l.chars, AnswerGrader.splitIntoCells(strokes, l.chars.size, cellSide), cellSide, deck.language,
            )
            WritingLayout.Line -> graph.grader.gradeLine(answer, strokes, width, height, deck.language)
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
        val strokeCount = (layout as? WritingLayout.Cells)?.chars?.sumOf { graph.references.get(it)?.strokes?.size ?: 3 } ?: 6
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
            usedHint = usedHint, ink = if (written) layer.drawing() else null, now = now,
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
fun ReviewScreen(deckId: String, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val state = remember(deckId) { ReviewState(graph, deckId) }
    val scope = rememberUiScope()
    LaunchedEffect(deckId) { ui { state.load() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Закрыть") }
            Column(Modifier.weight(1f)) {
                Text(state.deck?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("осталось ${state.queue.size} · повторено ${state.reviewed}", style = MaterialTheme.typography.labelMedium)
            }
            if (state.lastAnswer != null) {
                TextButton(onClick = { scope.launch { state.undoLast() } }) { Text("↶ Отменить оценку") }
            }
            if (state.current != null) {
                FilterChip(state.mode == AnswerMode.CLASSIC, { scope.launch { state.switchMode(AnswerMode.CLASSIC) } }, { Text("Классика") })
                Spacer(Modifier.width(6.dp))
                FilterChip(state.mode == AnswerMode.HANDWRITING, { scope.launch { state.switchMode(AnswerMode.HANDWRITING) } }, { Text("Письмо") })
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
                Text(state.question, fontSize = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (state.mode == AnswerMode.HANDWRITING) WritingAnswer(state) else ClassicAnswer(state)
                if (state.phase == Phase.ANSWER) RatingRow(state)
            }
        }
    }
}

@Composable
private fun ClassicAnswer(state: ReviewState) {
    if (state.phase == Phase.QUESTION) {
        Button(onClick = state::reveal, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Показать ответ") }
    } else {
        Text(state.answer, fontSize = 40.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

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
                val refs = cellsLayout?.chars?.mapNotNull { graph.references.get(it) }.orEmpty()
                Text("Новая карточка: посмотрите порядок черт и обведите по образцу", style = MaterialTheme.typography.titleSmall, color = HintColor)
                if (refs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { refs.forEach { StrokeOrderAnimation(it, Modifier.size(96.dp)) } }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.size(widthDp, heightDp).testTag("writing-canvas")) {
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
                                    if (state.phase == Phase.QUESTION) graph.references.get(ch)?.let { ref ->
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
                                    val ref = graph.references.get(cg.char) ?: return@forEachIndexed
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(state.answer, fontSize = 40.sp, fontWeight = FontWeight.Medium)
        // stroke order of the answer, animated (tap to replay)
        val refs = (state.layout as? WritingLayout.Cells)?.chars?.mapNotNull { graph.references.get(it) }.orEmpty()
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
            Button(onClick = { scope.launch { state.rate(r) } }, colors = colors, modifier = Modifier.weight(1f).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r.label(), maxLines = 1)
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
