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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wanki.data.AnswerMode
import app.wanki.data.DeckEntity
import app.wanki.graph
import app.wanki.ui.browser.CardBrowser
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.languageName
import kotlinx.coroutines.launch

/** A deck: what is due, its subdecks, and its cards (subdecks included) with search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeckScreen(
    deckId: String,
    onBack: () -> Unit,
    onStudy: (String) -> Unit,
    onOpenDeck: (String) -> Unit = {},
    onOptions: () -> Unit = {},
    onOpenCard: (String) -> Unit = {},
) {
    val graph = LocalContext.current.graph
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val tree by remember(now) { graph.repository.observeDeckTree(now) }.collectAsState(null)
    val t = tree ?: return
    val deck = t.byId[deckId]
    if (deck == null) {
        // deleted (from the options screen)
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val row = remember(t) { t.row(deckId) } ?: return
    val subtree = remember(t) { t.subtree(deckId) }
    val children = remember(t) { t.children(deckId).mapNotNull { t.row(it.id) } }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                deck.name, onBack = onBack,
                subtitle = listOfNotNull(
                    deck.parentId?.let { t.path(it) },
                    languageName(deck.language),
                    "${row.total} карт.",
                ).joinToString(" · "),
            ) {
                TextButton(onClick = onOptions, modifier = Modifier.testTag("deck-options")) { Text("⚙ Настройки") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сегодня", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("новых ${row.newCount}", color = NewColor, fontWeight = FontWeight.SemiBold, softWrap = false)
                            Text("изучаемых ${row.learnCount}", color = LearnColor, fontWeight = FontWeight.SemiBold, softWrap = false)
                            Text("повторить ${row.reviewCount}", color = DueColor, fontWeight = FontWeight.SemiBold, softWrap = false)
                        }
                    }
                    Button(onClick = { onStudy(deckId) }, enabled = row.newCount + row.dueCount > 0) { Text("Учить") }
                }
            }
            CardBrowser(
                deckIds = subtree,
                tree = t,
                onOpenCard = onOpenCard,
                modifier = Modifier.weight(1f),
                header = {
                    if (children.isNotEmpty()) {
                        item("subdecks-title") { Text("Вложенные колоды", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                        children.forEach { c ->
                            item("subdeck-${c.deck.id}") {
                                Card(Modifier.fillMaxWidth().clickable { onOpenDeck(c.deck.id) }) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.deck.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Counts(c.newCount, c.learnCount, c.reviewCount)
                                    }
                                }
                            }
                        }
                        item("cards-title") { Text("Карточки", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    }
                },
            )
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Карточка")
        }
    }
    if (adding) AddCardDialog(deck, onDismiss = { adding = false })
}

@Composable
private fun AddCardDialog(deck: DeckEntity, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AnswerMode.valueOf(deck.defaultAnswerMode)) }
    var added by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(front, { front = it }, label = { Text("Вопрос (лицевая сторона)") })
                OutlinedTextField(back, { back = it }, label = { Text("Ответ (то, что пишется)") })
                OutlinedTextField(tags, { tags = it }, label = { Text("Теги через пробел") }, singleLine = true)
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
                    val tg = tags
                    graph.appScope.launch { graph.repository.addCard(deck.id, f, b, mode, tg) }
                    front = ""; back = ""; added++
                },
            ) { Text("Добавить") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Готово") } },
    )
}
