package app.wanki.ui.browser

import app.wanki.ui.Route
import app.wanki.ui.common.rememberSourceOpener
import app.wanki.ui.common.SourceTarget
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.anki.CardRenderer
import app.wanki.anki.NoteKind
import app.wanki.anki.NoteType
import app.wanki.data.AnswerMode
import app.wanki.data.CardWithNote
import app.wanki.data.DeckTree
import app.wanki.data.InkAttemptEntity
import app.wanki.data.NoteEntity
import app.wanki.data.ReviewLogEntity
import app.wanki.graph
import app.wanki.ink.InkCodec
import app.wanki.review.WritingLayout
import app.wanki.srs.Rating
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.DeckPickerDialog
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import app.wanki.ui.decks.ModeChips
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))

/** Edits a card; changes are saved when leaving. Shows its schedule and answer history. */
@Composable
fun CardEditorScreen(cardId: String, onBack: () -> Unit, onPractice: (String, String) -> Unit = { _, _ -> }, onOpenSource: (Route) -> Unit = {}) {
    val graph = LocalContext.current.graph
    val card by remember(cardId) { graph.repository.observeCard(cardId) }.collectAsState(null)
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    val type by produceState<NoteType?>(null, card?.note?.noteTypeId) {
        value = card?.note?.noteTypeId?.let { ui { graph.repository.noteType(it) } }
    }
    val c = card ?: return
    val t = tree ?: return
    CardEditor(c, type ?: return, t, onBack, onPractice, onOpenSource)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardEditor(c: CardWithNote, type: NoteType, tree: DeckTree, onBack: () -> Unit, onPractice: (String, String) -> Unit, onOpenSource: (Route) -> Unit) {
    val graph = LocalContext.current.graph
    val original = remember(c.note.fields, type) {
        val v = c.note.fields.split(NoteEntity.FIELD_SEPARATOR)
        List(maxOf(v.size, type.fields.size)) { v.getOrElse(it) { "" } }
    }
    val values = remember(c.card.id, original) { mutableStateListOf(*original.toTypedArray()) }
    var tags by remember(c.card.id) { mutableStateOf(c.note.tags) }
    var deckId by remember(c.card.id) { mutableStateOf(c.card.deckId) }
    var mode by remember(c.card.id) { mutableStateOf(AnswerMode.valueOf(c.card.answerMode)) }
    var writeOrd by remember(c.card.id) { mutableStateOf(c.card.writeFieldOrd) }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val history by produceState(emptyList<Pair<ReviewLogEntity, InkAttemptEntity?>>(), c.card.reps, c.card.updatedAt) {
        value = ui { graph.repository.cardHistory(c.card.id) }
    }
    val dirty = values.toList() != original || tags != c.note.tags || deckId != c.card.deckId ||
        mode.name != c.card.answerMode || writeOrd != c.card.writeFieldOrd
    val valid = values.any { it.isNotBlank() }

    fun saveAndClose() {
        if (dirty && valid) {
            val f = values.toList()
            val tg = tags
            val d = deckId
            val m = mode
            val w = writeOrd
            graph.appScope.launch { graph.repository.saveCard(c, f, tg, d, m, w) }
        }
        onBack()
    }
    BackHandler { saveAndClose() }
    val source = rememberSourceOpener(c.note.id) { route -> saveAndClose(); onOpenSource(route) }
    // a linked card (from tape) belongs to its page: its fields change there
    val linked by produceState(false, c.note.id) { value = ui { graph.repository.sourceOf(c.note.id)?.linked == true } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Карточка", onBack = ::saveAndClose, subtitle = tree.title(deckId)) {
            Button(onClick = ::saveAndClose, enabled = valid) { Text(if (dirty) "Сохранить" else "Готово") }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!type.isBasic) Text("Тип заметки: ${type.name}", style = MaterialTheme.typography.labelMedium)
            values.indices.forEach { i ->
                OutlinedTextField(
                    values[i], { values[i] = it },
                    label = { Text(type.fields.getOrElse(i) { "Поле ${i + 1}" }) },
                    readOnly = linked,
                    modifier = Modifier.fillMaxWidth().testTag("field-$i"),
                )
            }
            OutlinedTextField(tags, { tags = it }, label = { Text("Теги через пробел") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Колода: " + tree.title(deckId), Modifier.weight(1f))
                TextButton(onClick = { picking = true }) { Text("Изменить") }
            }
            Text("Как отвечать", style = MaterialTheme.typography.titleSmall)
            ModeChips(mode) { mode = it }
            if (mode == AnswerMode.HANDWRITING) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Писать:", Modifier.align(Alignment.CenterVertically))
                    if (type.isBasic) {
                        FilterChip(writeOrd == 1, { writeOrd = 1 }, { Text("ответ") })
                        FilterChip(writeOrd == 0, { writeOrd = 0 }, { Text("вопрос") })
                    } else if (type.kind != NoteKind.CLOZE) {
                        type.fields.forEachIndexed { i, name -> FilterChip(writeOrd == i, { writeOrd = i }, { Text(name) }) }
                    } else {
                        Text("пропуски cloze", Modifier.align(Alignment.CenterVertically))
                    }
                }
                val language = tree.byId[deckId]?.language ?: "ja"
                val edited = c.note.copy(fields = values.joinToString(NoteEntity.FIELD_SEPARATOR.toString()))
                val written = CardRenderer.render(type, edited, c.card.templateOrd, writeOrd).writeAnswer
                ReferenceInfo(written, language) { ch -> onPractice(ch, language) }
            }
            HorizontalDivider()
            Schedule(c)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.target != null) {
                    OutlinedButton(onClick = source::open, enabled = source.enabled, modifier = Modifier.testTag("open-source")) {
                        Text(if (linked && source.target !is SourceTarget.Gone) "Изменить на странице" else source.label)
                    }
                }
                OutlinedButton(onClick = { graph.appScope.launch { graph.repository.setSuspended(listOf(c.card.id), !c.card.suspended) } }) {
                    Text(if (c.card.suspended) "Вернуть из приостановки" else "Приостановить")
                }
                OutlinedButton(onClick = { graph.appScope.launch { graph.repository.buryUntilTomorrow(listOf(c.card.id)) } }) {
                    Text("Отложить до завтра")
                }
                OutlinedButton(onClick = {
                    confirm = "Сбросить прогресс? Карточка снова станет новой, история ответов сохранится." to {
                        graph.appScope.launch { graph.repository.resetCards(listOf(c.card.id)) }
                    }
                }) { Text("Сбросить прогресс") }
                OutlinedButton(onClick = {
                    confirm = "Удалить карточку?" to {
                        graph.appScope.launch { graph.repository.deleteCard(c.card.id) }
                        onBack()
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
            Text("История ответов", style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) Text("Ответов ещё не было", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.asReversed().forEach { (log, ink) -> HistoryRow(log, ink) }
        }
    }
    if (picking) {
        DeckPickerDialog(tree, "Колода", deckId, onPick = { d -> if (d != null) deckId = d.id; picking = false }, onDismiss = { picking = false })
    }
    confirm?.let { (msg, action) -> ConfirmDialog("Подтверждение", msg, "Да", onConfirm = action, onDismiss = { confirm = null }) }
}

/** Which characters of the written side have stroke references (others are checked by the recognizer only). */
@Composable
private fun ReferenceInfo(answer: String, language: String, onPractice: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val layout = WritingLayout.forAnswer(answer)
    if (layout !is WritingLayout.Cells) {
        Text(
            "Ответ пишется строкой и проверяется распознаванием рукописи.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val missing = layout.chars.filter { graph.references.get(it, language) == null }
    Text(
        if (missing.isEmpty()) "Для всех знаков есть эталоны: проверяются черты, их порядок и почерк."
        else "Нет эталона для: ${missing.joinToString(" ")} — эти знаки проверяются только распознаванием.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val practicable = layout.chars.filter { graph.references.get(it, language) != null }.distinct()
    if (practicable.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            practicable.forEach { ch -> OutlinedButton(onClick = { onPractice(ch) }) { Text("Тренировать $ch") } }
        }
    }
}

@Composable
private fun Schedule(c: CardWithNote) {
    val card = c.card
    val zone = ZoneId.systemDefault()
    val lines = buildList {
        add(
            "Состояние: " + when {
                card.suspended -> "приостановлена"
                card.isNew -> "новая"
                card.state == "REVIEW" -> "выучена"
                card.state == "RELEARNING" -> "переучивается"
                else -> "изучается"
            },
        )
        if (!card.isNew) {
            add("Следующий показ: " + Instant.ofEpochMilli(card.due).atZone(zone).format(dateTime))
            card.lastReview?.let { add("Последний ответ: " + Instant.ofEpochMilli(it).atZone(zone).format(dateTime)) }
            card.stability?.let { add("Стабильность: %.1f д".format(it)) }
            card.difficulty?.let { add("Сложность: %.1f из 10".format(it)) }
            add("Ответов: ${card.reps}, забываний: ${card.lapses}")
        }
        if (card.buriedUntil > System.currentTimeMillis()) add("Отложена до " + Instant.ofEpochMilli(card.buriedUntil).atZone(zone).format(date))
    }
    Text("Расписание", style = MaterialTheme.typography.titleSmall)
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun HistoryRow(log: ReviewLogEntity, ink: InkAttemptEntity?) {
    val zone = ZoneId.systemDefault()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ink != null) InkThumbnail(ink.strokes, Modifier.size(64.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Instant.ofEpochMilli(log.reviewedAt).atZone(zone).format(dateTime) + " · " + ratingLabel(log.rating),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    if (log.mode == AnswerMode.HANDWRITING.name) "письмо" else "классика",
                    log.score?.let { "%.0f%%".format(it) },
                    log.beauty?.let { "почерк %.0f".format(it) },
                    "%.1f с".format(log.durationMs / 1000.0),
                    "с подсказкой".takeIf { log.usedHint },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun ratingLabel(value: Int) = when (Rating.entries.firstOrNull { it.value == value }) {
    Rating.AGAIN -> "Снова"
    Rating.HARD -> "Трудно"
    Rating.GOOD -> "Хорошо"
    Rating.EASY -> "Легко"
    null -> "—"
}

/** A stored handwriting attempt, scaled to fit, on paper. */
@Composable
fun InkThumbnail(blob: ByteArray, modifier: Modifier = Modifier) {
    val strokes = remember(blob) { runCatching { InkCodec.decode(blob).strokes }.getOrDefault(emptyList()) }
    Surface(color = Color(0xFFFAF8F2), shape = MaterialTheme.shapes.small, modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val pts = strokes.flatMap { it.points }
            if (pts.isEmpty()) return@Canvas
            val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
            val k = minOf(size.width / (maxX - minX).coerceAtLeast(1f), size.height / (maxY - minY).coerceAtLeast(1f))
            val ox = (size.width - (maxX - minX) * k) / 2
            val oy = (size.height - (maxY - minY) * k) / 2
            for (s in strokes) {
                val path = Path()
                s.points.forEachIndexed { i, p ->
                    val o = Offset(ox + (p.x - minX) * k, oy + (p.y - minY) * k)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, Color(0xFF1A1A1A), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
