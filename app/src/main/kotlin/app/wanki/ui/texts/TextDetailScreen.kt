package app.wanki.ui.texts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.graph
import app.wanki.texts.TextSchedule
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.MenuButton
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import kotlinx.coroutines.launch

private enum class PieceStatus(val label: String) { NEW("новый"), LEARNED("выучен"), SABQI("в сабки"), MANZIL("в манзиле"), DUE("к повторению") }

private fun status(p: TextPieceEntity, schedule: TextSchedule, now: Long) = when {
    p.learnedAt == null -> PieceStatus.NEW
    schedule == TextSchedule.HIFZ -> if (p.sabqiLeft > 0) PieceStatus.SABQI else PieceStatus.MANZIL
    p.due <= now -> PieceStatus.DUE
    else -> PieceStatus.LEARNED
}

@Composable
fun TextDetailScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val graph = LocalContext.current.graph
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val text by remember(id) { graph.texts.observeText(id) }.collectAsState(null)
    val pieces by remember(id) { graph.texts.observePieces(id) }.collectAsState(null)
    // keyed on "now" as well: a practice recital only adds log rows, the pieces stay the same
    val weak by produceState(emptySet<String>(), pieces, now) { value = ui { graph.texts.weakPieces(id) } }
    val portion by produceState(emptyList<String>(), text, pieces, now) { value = ui { graph.texts.nextPortion(id, now) } }
    var marking by remember { mutableStateOf<TextPieceEntity?>(null) }
    var switching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val t = text
    val ps = pieces
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t?.title.orEmpty(), onBack = onBack, subtitle = t?.let { "${textLanguageName(it.language)} · ${kindName(it.kind)}" }) {
            // "Изменить" sits with the other actions below: header actions squeeze the title at large font sizes
            if (t != null && !t.deleted) MenuButton("⋮", listOf("Изменить" to { onEdit(id) }, "Удалить" to { deleting = true }))
        }
        if (t == null || ps == null) return@Column
        if (t.deleted) {
            Text("Текст удалён", modifier = Modifier.padding(16.dp))
            return@Column
        }
        val schedule = scheduleOf(t.schedule)
        val onMark = { p: TextPieceEntity -> if (ps.any { it.learnedAt == null && it.pieceIndex <= p.pieceIndex }) marking = p }
        val summary = @Composable {
            Summary(
                t, ps, portion.size,
                onEdit = { onEdit(id) },
                onPause = { graph.appScope.launch { graph.texts.setActive(id, !t.active) } },
                onSwitch = { switching = true },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("text-detail")) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { summary() }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pieceItems(ps, t, schedule, now, weak, onMark)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Column(Modifier.padding(bottom = 8.dp)) { summary() } }
                    pieceItems(ps, t, schedule, now, weak, onMark)
                }
            }
        }

        marking?.let { p ->
            val count = ps.count { it.learnedAt == null && it.pieceIndex <= p.pieceIndex }
            AlertDialog(
                onDismissRequest = { marking = null },
                title = { Text("Отметить выученным до этого места?") },
                text = {
                    Text(
                        "Куски 1–${p.pieceIndex + 1}, которые ещё не выучены (${piecesCount(count)}), станут выученными и не попадут в новые порции. " +
                            if (schedule == TextSchedule.FSRS) "По FSRS они получат оценку «Легко»." else "Они сразу пойдут в манзиль.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        marking = null
                        graph.appScope.launch { graph.texts.markLearned(id, p.pieceIndex) }
                    }) { Text("Отметить") }
                },
                dismissButton = { TextButton(onClick = { marking = null }) { Text("Отмена") } },
            )
        }
        if (switching) {
            val to = if (schedule == TextSchedule.FSRS) TextSchedule.HIFZ else TextSchedule.FSRS
            AlertDialog(
                onDismissRequest = { switching = false },
                title = { Text("Сменить расписание?") },
                text = {
                    Text(
                        "Текст перейдёт на «${scheduleName(to)}». " +
                            if (to == TextSchedule.HIFZ) "Недавно выученные куски попадут в сабки, остальные — в манзиль. История повторений сохранится."
                            else "Сроки повторения пересчитаются по истории ответов, она сохранится.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        switching = false
                        graph.appScope.launch { graph.texts.switchSchedule(id, to) }
                    }) { Text("Перейти") }
                },
                dismissButton = { TextButton(onClick = { switching = false }) { Text("Отмена") } },
            )
        }
        if (deleting) {
            ConfirmDialog(
                "Удалить текст?", "«${t.title}» пропадёт из списка и из плана на сегодня.", "Удалить",
                onConfirm = {
                    graph.appScope.launch { graph.texts.deleteText(id) }
                    onBack()
                },
                onDismiss = { deleting = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(
    t: TextEntity, pieces: List<TextPieceEntity>, portion: Int, onEdit: () -> Unit, onPause: () -> Unit, onSwitch: () -> Unit,
) {
    val learned = pieces.count { it.learnedAt != null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Выучено $learned из ${pieces.size}", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { if (pieces.isEmpty()) 0f else learned / pieces.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(scheduleName(t.schedule))
            if (!t.active) Badge("пауза", color = MaterialTheme.colorScheme.tertiaryContainer)
        }
        Text(
            when {
                pieces.isNotEmpty() && learned == pieces.size -> "Все куски выучены — дальше только повторения."
                !t.active -> "Пауза: новых порций нет, повторения идут."
                portion > 0 -> "Сегодня новая порция: ${piecesCount(portion)}."
                else -> "Следующая порция — завтра."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Изменить") }
            OutlinedButton(onClick = onPause) { Text(if (t.active) "Пауза" else "Продолжить") }
            OutlinedButton(onClick = onSwitch) { Text("Расписание: FSRS ↔ Сабак·сабки·манзиль") }
        }
        Text(
            "Долгое нажатие на кусок — отметить выученным всё до этого места.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.pieceItems(
    pieces: List<TextPieceEntity>, t: TextEntity, schedule: TextSchedule, now: Long, weak: Set<String>, onMark: (TextPieceEntity) -> Unit,
) {
    items(pieces, key = { it.id }) { p -> PieceRow(p, t.language, status(p, schedule, now), p.id in weak) { onMark(p) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PieceRow(p: TextPieceEntity, language: String, status: PieceStatus, weak: Boolean, onLongPress: () -> Unit) {
    val colors = if (weak) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        // the whole row is mirrored for RTL, so the number stands where the line starts
        Directional(language) {
            Row(
                Modifier.clip(CardDefaults.shape)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Отметить выученным до этого места")
                    .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "${p.pieceIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(28.dp).padding(top = 2.dp, end = 8.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.content, style = pieceStyle(language), modifier = Modifier.fillMaxWidth())
                    if (p.note.isNotBlank()) {
                        Text(p.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Badge(status.label, color = statusColor(status))
                    if (weak) Text("слабый", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: PieceStatus): Color = when (s) {
    PieceStatus.NEW -> MaterialTheme.colorScheme.primaryContainer
    PieceStatus.DUE -> MaterialTheme.colorScheme.tertiaryContainer
    PieceStatus.SABQI -> MaterialTheme.colorScheme.secondaryContainer
    PieceStatus.LEARNED, PieceStatus.MANZIL -> MaterialTheme.colorScheme.surfaceVariant
}
