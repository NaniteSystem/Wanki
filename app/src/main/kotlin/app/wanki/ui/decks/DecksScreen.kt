package app.wanki.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wanki.data.AnswerMode
import app.wanki.data.CardWithNote
import app.wanki.data.DeckEntity
import app.wanki.graph
import app.wanki.ui.common.Languages
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.languageName
import kotlinx.coroutines.launch

private val NewColor = Color(0xFF1E5BD8)
private val DueColor = Color(0xFF2E9E5B)

@Composable
fun DecksScreen(onOpen: (String) -> Unit, onStudy: (String) -> Unit) {
    val graph = LocalContext.current.graph
    // "now" is refreshed on resume so due counts stay current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val decks by remember(now) { graph.repository.observeDecks(now) }.collectAsState(emptyList())
    val reviewedToday by remember { graph.repository.observeReviewedToday() }.collectAsState(0)
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.repository.seedIfEmpty() }
    // download recognition models for the decks' languages in the background
    LaunchedEffect(decks.map { it.deck.language }.toSet()) {
        decks.map { it.deck.language }.toSet().forEach { graph.recognizers.get(it).prefetch() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Колоды", subtitle = "Сегодня повторено: $reviewedToday")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(decks, key = { it.deck.id }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(d.deck.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.deck.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${languageName(d.deck.language)} · ${d.totalCount} карт. · " +
                                        if (d.deck.defaultAnswerMode == AnswerMode.HANDWRITING.name) "письмо" else "классика",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("${d.newCount}", color = NewColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                            Text("${d.dueCount}", color = DueColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                            Button(onClick = { onStudy(d.deck.id) }, enabled = d.newCount + d.dueCount > 0) { Text("Учить") }
                        }
                    }
                }
                item { Box(Modifier.padding(40.dp)) }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Колода")
        }
    }
    if (creating) {
        CreateDeckDialog(onDismiss = { creating = false }) { name, lang, mode ->
            creating = false
            graph.appScope.launch { onOpen(graph.repository.createDeck(name, lang, mode).id) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDeckDialog(onDismiss: () -> Unit, onCreate: (String, String, AnswerMode) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("ja") }
    var mode by remember { mutableStateOf(AnswerMode.HANDWRITING) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая колода") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                Text("Язык", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Languages.forEach { (code, n) -> FilterChip(lang == code, { lang = code }, { Text(n) }) }
                }
                Text("Ответ по умолчанию", style = MaterialTheme.typography.labelLarge)
                ModeChips(mode) { mode = it }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.ifBlank { "Колода" }, lang, mode) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ModeChips(mode: AnswerMode, onChange: (AnswerMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(mode == AnswerMode.CLASSIC, { onChange(AnswerMode.CLASSIC) }, { Text("Классика") })
        FilterChip(mode == AnswerMode.HANDWRITING, { onChange(AnswerMode.HANDWRITING) }, { Text("Письмо") })
    }
}

/** Deck: counts, study button, card list (a small browser) and adding cards. */
@Composable
fun DeckScreen(deckId: String, onBack: () -> Unit, onStudy: () -> Unit) {
    val graph = LocalContext.current.graph
    val deck by remember(deckId) { graph.repository.observeDeck(deckId) }.collectAsState(null)
    val cards by remember(deckId) { graph.repository.observeCards(deckId) }.collectAsState(emptyList())
    var adding by remember { mutableStateOf(false) }
    val d = deck ?: return
    val now = System.currentTimeMillis()
    val newCount = cards.count { it.card.lastReview == null }
    val dueCount = cards.count { it.card.lastReview != null && it.card.due <= now }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(d.name, onBack = onBack, subtitle = "${languageName(d.language)} · новых $newCount · к повторению $dueCount") {
                Button(onClick = onStudy, enabled = newCount + dueCount > 0) { Text("Учить") }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (cards.isEmpty()) item { Text("Карточек пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(cards, key = { it.card.id }) { c -> CardRow(c) }
                item { Box(Modifier.padding(40.dp)) }
            }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Карточка")
        }
    }
    if (adding) AddCardDialog(d, onDismiss = { adding = false })
}

@Composable
private fun CardRow(c: CardWithNote) {
    val graph = LocalContext.current.graph
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.note.field(0), maxLines = 1)
                Text(c.note.field(1), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            AssistChip(
                onClick = {
                    val next = if (c.card.answerMode == AnswerMode.HANDWRITING.name) AnswerMode.CLASSIC else AnswerMode.HANDWRITING
                    graph.appScope.launch { graph.repository.setAnswerMode(c.card, next) }
                },
                label = { Text(if (c.card.answerMode == AnswerMode.HANDWRITING.name) "письмо" else "классика") },
            )
            TextButton(onClick = { graph.appScope.launch { graph.repository.deleteCard(c.card.id) } }) { Text("✕") }
        }
    }
}

@Composable
private fun AddCardDialog(deck: DeckEntity, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AnswerMode.valueOf(deck.defaultAnswerMode)) }
    var added by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text("Вопрос (лицевая сторона)") })
                OutlinedTextField(back, { back = it }, label = { Text("Ответ (то, что пишется)") })
                ModeChips(mode) { mode = it }
                if (added > 0) Text("Добавлено: $added", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = front.isNotBlank() && back.isNotBlank(),
                onClick = {
                    val f = front
                    val b = back
                    graph.appScope.launch { graph.repository.addCard(deck.id, f, b, mode) }
                    front = ""; back = ""; added++
                },
            ) { Text("Добавить") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Готово") } },
    )
}
