package app.wanki.ui.notebooks

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.graph
import app.wanki.ui.common.CardSource
import app.wanki.ui.common.DeckPickerDialog
import app.wanki.ui.common.chooseDeck
import app.wanki.ui.common.ui
import kotlinx.coroutines.launch

/** «Карточки из скотча»: one linked card per mask of the page that has none yet, into the chosen deck. */
@Composable
fun MaskCardsDialog(source: CardSource, flush: suspend () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    val deckKey = source.notebookId ?: source.sourceId
    val count by produceState<Int?>(null) { value = ui { flush(); graph.repository.masksWithoutCards(source.sourceId).size } }
    var deckId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(tree) {
        val t = tree ?: return@LaunchedEffect
        if (deckId == null || deckId !in t.byId) {
            deckId = chooseDeck(graph.settings.lastDeckFor(deckKey), t.byId.values.toList(), source.language, graph.settings.lastDeck)
        }
    }
    val n = count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Карточки из скотча") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (n) {
                        null -> "Считаю…"
                        0 -> "У всех полосок скотча на странице уже есть карточки."
                        else -> "Новых карточек: $n. Каждая показывает страницу с закрытой полоской, ответ — открытая."
                    },
                    modifier = Modifier.testTag("mask-cards-count"),
                )
                AssistChip(
                    onClick = { picking = true }, enabled = tree != null,
                    label = { Text("Колода: " + (deckId?.let { tree?.title(it) } ?: "выбрать"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("mask-cards-deck"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = n != null && n > 0 && deckId != null,
                modifier = Modifier.testTag("mask-cards-add"),
                onClick = {
                    val deck = deckId ?: return@TextButton
                    graph.settings.setLastDeckFor(deckKey, deck)
                    val app = context.applicationContext
                    graph.appScope.launch {
                        runCatching { graph.repository.addMaskCards(deck, source.sourceId) }.onFailure {
                            Log.e("Wanki", "mask cards not saved", it)
                            ui { Toast.makeText(app, "Не удалось сохранить карточки", Toast.LENGTH_LONG).show() }
                        }
                    }
                    onDismiss()
                },
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    val t = tree
    if (picking && t != null) {
        DeckPickerDialog(t, "Колода", deckId, onPick = { d -> if (d != null) deckId = d.id; picking = false }, onDismiss = { picking = false })
    }
}
