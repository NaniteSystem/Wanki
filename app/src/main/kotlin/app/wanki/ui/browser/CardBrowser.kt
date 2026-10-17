package app.wanki.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.anki.CardHtml
import app.wanki.data.AnswerMode
import app.wanki.data.CardFilter
import app.wanki.data.CardQuery
import app.wanki.data.CardSort
import app.wanki.data.CardWithNote
import app.wanki.data.DeckTree
import app.wanki.graph
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.DeckPickerDialog
import app.wanki.ui.common.MenuButton
import app.wanki.ui.common.ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val filters = listOf(
    CardFilter.ALL to "Все", CardFilter.NEW to "Новые", CardFilter.LEARNING to "Изучаемые",
    CardFilter.REVIEW to "Выученные", CardFilter.DUE to "К повторению", CardFilter.SUSPENDED to "Приостановленные",
)

private val sorts = listOf(
    CardSort.POSITION to "по порядку добавления", CardSort.DUE to "по дате показа",
    CardSort.QUESTION to "по вопросу", CardSort.ANSWER to "по ответу", CardSort.CHANGED to "недавно изменённые",
)

/**
 * Card list with search, filters and bulk actions (long press selects). [deckIds] fixes the
 * decks (a deck screen); null searches everywhere with a deck filter. [header] goes above the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardBrowser(
    deckIds: List<String>?,
    tree: DeckTree,
    onOpenCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
) {
    val graph = LocalContext.current.graph
    val scope = app.wanki.ui.common.rememberUiScope()
    var text by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(text) { delay(250); debounced = text }
    var filter by remember { mutableStateOf(CardFilter.ALL) }
    var sort by remember { mutableStateOf(CardSort.POSITION) }
    var deckFilter by remember { mutableStateOf<String?>(null) }
    var pickingDeck by remember { mutableStateOf(false) }
    val query = CardQuery(
        text = debounced,
        deckIds = deckIds ?: deckFilter?.let { tree.subtree(it) },
        filter = filter,
        sort = sort,
    )
    val cards by remember(query) { graph.repository.observeCards(query) }.collectAsState(emptyList())
    val total by remember(query) { graph.repository.observeCardCount(query) }.collectAsState(0)
    val selected = remember { mutableStateListOf<String>() }
    var moving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val now = remember(cards) { System.currentTimeMillis() }

    fun act(block: suspend (List<String>) -> Unit) {
        val ids = selected.toList()
        selected.clear()
        graph.appScope.launch { block(ids) }
    }

    Column(modifier) {
        if (selected.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("Поиск по вопросу, ответу, тегам") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("card-search"),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { (f, label) -> FilterChip(filter == f, { filter = f }, { Text(label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Найдено: $total", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (deckIds == null) {
                        TextButton(onClick = { pickingDeck = true }) {
                            Text("Колода: " + (deckFilter?.let { tree.byId[it]?.name } ?: "все"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    MenuButton("Порядок ▾", sorts.map { (s, label) -> ((if (s == sort) "● " else "") + label) to { sort = s } })
                }
            }
        } else {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected.clear() }) { Text("✕") }
                    Text("Выбрано: ${selected.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        scope.launch {
                            val all = ui { graph.repository.cardIds(query) }
                            selected.clear(); selected.addAll(all)
                        }
                    }) { Text("Все") }
                    MenuButton(
                        "Действия ▾",
                        listOf(
                            "Переместить в колоду…" to { moving = true },
                            "Приостановить" to { act { graph.repository.setSuspended(it, true) } },
                            "Вернуть из приостановки" to { act { graph.repository.setSuspended(it, false) } },
                            "Отложить до завтра" to { act { graph.repository.buryUntilTomorrow(it) } },
                            "Ответ письмом" to { act { graph.repository.setAnswerMode(it, AnswerMode.HANDWRITING) } },
                            "Ответ классикой" to { act { graph.repository.setAnswerMode(it, AnswerMode.CLASSIC) } },
                            "Сбросить прогресс…" to {
                                confirm = "Сбросить прогресс ${selected.size} карт.? Они снова станут новыми, история ответов сохранится." to {
                                    act { graph.repository.resetCards(it) }
                                }
                            },
                            "Удалить…" to {
                                confirm = "Удалить ${selected.size} карт.?" to { act { graph.repository.deleteCards(it) } }
                            },
                        ),
                    )
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("card-list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            header()
            if (cards.isEmpty()) item {
                Text(
                    if (debounced.isBlank() && filter == CardFilter.ALL) "Карточек пока нет" else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            items(cards, key = { it.card.id }) { c ->
                val isSelected = c.card.id in selected
                CardRow(
                    c, now, isSelected,
                    showDeck = deckIds == null || deckIds.size > 1,
                    deckName = tree.byId[c.card.deckId]?.name.orEmpty(),
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            when {
                                selected.isEmpty() -> onOpenCard(c.card.id)
                                isSelected -> selected.remove(c.card.id)
                                else -> selected.add(c.card.id)
                            }
                        },
                        onLongClick = { if (isSelected) selected.remove(c.card.id) else selected.add(c.card.id) },
                    ),
                )
            }
            if (total > cards.size) item {
                Text(
                    "Показаны первые ${cards.size} из $total — уточните поиск",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item { Column(Modifier.padding(40.dp)) {} }
        }
    }
    if (pickingDeck) {
        DeckPickerDialog(
            tree, "Искать в колоде", deckFilter,
            onPick = { deckFilter = it?.id; pickingDeck = false }, onDismiss = { pickingDeck = false }, allowNone = "Во всех колодах",
        )
    }
    if (moving) {
        DeckPickerDialog(
            tree, "Переместить в колоду", null,
            onPick = { d -> moving = false; if (d != null) act { graph.repository.moveCards(it, d.id) } },
            onDismiss = { moving = false },
        )
    }
    confirm?.let { (msg, action) ->
        ConfirmDialog("Подтверждение", msg, "Да", onConfirm = action, onDismiss = { confirm = null })
    }
}

@Composable
private fun CardRow(c: CardWithNote, now: Long, selected: Boolean, showDeck: Boolean, deckName: String, modifier: Modifier) {
    val colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(CardHtml.plain(c.note.field(0)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    CardHtml.plain(c.note.field(if (c.card.writeFieldOrd == 0) 1 else c.card.writeFieldOrd)),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (showDeck || c.note.tags.isNotBlank()) {
                    Text(
                        listOfNotNull(deckName.takeIf { showDeck }, c.note.tags.takeIf { it.isNotBlank() }?.let { "#" + it.replace(" ", " #") })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(cardStatus(c, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (c.card.answerMode == AnswerMode.HANDWRITING.name) "письмо" else "классика",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Short state for lists: new, suspended, buried, or when it is due. */
fun cardStatus(c: CardWithNote, now: Long): String = when {
    c.card.suspended -> "приостановлена"
    c.card.buriedUntil > now -> "отложена"
    c.card.isNew -> "новая"
    c.card.due <= now -> "сейчас"
    else -> "через " + formatDuration(c.card.due - now)
}

fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "${min.coerceAtLeast(1)} мин"
        min < 60 * 24 -> "${min / 60} ч"
        min < 60 * 24 * 30 -> "${min / (60 * 24)} д"
        min < 60 * 24 * 365 -> "%.1f мес".format(min / (60.0 * 24 * 30))
        else -> "%.1f г".format(min / (60.0 * 24 * 365))
    }
}
