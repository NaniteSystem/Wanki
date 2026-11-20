package app.wanki.ui.common

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.data.AnswerMode
import app.wanki.data.DeckEntity
import app.wanki.data.SourceKind
import app.wanki.data.SourceRefEntity
import app.wanki.graph
import app.wanki.ink.InkRect
import app.wanki.recognition.TranslationService
import app.wanki.review.WritingLayout
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a card is made from: [sourceId] is a page id for [SourceKind.PAGE]; the last deck is remembered per [notebookId]. */
data class CardSource(val kind: SourceKind, val sourceId: String, val notebookId: String?, val language: String)

/** What is selected: recognised [text], its [bounds] in world units (null = the whole source) and a [picture] of the ink. */
data class SelectionContent(val text: String, val bounds: InkRect?, val picture: Bitmap?)

/** Last deck for this notebook if it still exists → first deck in [language] → [lastUsed] (if it exists) → null. */
fun chooseDeck(lastForSource: String?, decks: List<DeckEntity>, language: String, lastUsed: String?): String? {
    val ids = decks.mapTo(HashSet()) { it.id }
    return lastForSource?.takeIf { it in ids }
        ?: decks.firstOrNull { it.language == language }?.id
        ?: lastUsed?.takeIf { it in ids }
}

/**
 * One card from a selection: «Ответ» = recognised text, «Вопрос» = its translation, optionally the handwriting as a
 * picture after the answer. [read] is called on open and again on «Добавить ещё», so it sees the current selection.
 * Cards made here are one-off (not linked to the source).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardFromSelectionDialog(
    source: CardSource,
    read: suspend () -> SelectionContent?,
    onDismiss: () -> Unit,
    /** Answer and question already edited elsewhere (text dialog): used for the first card instead of [read]'s text. */
    initial: Pair<String, String?>? = null,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberUiScope()
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    val deckKey = source.notebookId ?: source.sourceId
    var generation by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<SelectionContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var answer by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(false) }
    var deckId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(generation) {
        loading = true
        val c = read()
        content = c
        val seed = initial?.takeIf { generation == 0 }
        answer = seed?.first ?: c?.text.orEmpty()
        question = seed?.second.orEmpty()
        attach = c?.picture != null
        loading = false
        if (answer.isNotBlank() && question.isEmpty()) {
            val t = graph.translation.translate(answer, graph.settings.translateTo)
            if (t is TranslationService.Result.Ok && question.isEmpty()) question = t.text
        }
    }
    LaunchedEffect(tree) {
        val t = tree ?: return@LaunchedEffect
        if (deckId == null || deckId !in t.byId) {
            deckId = chooseDeck(graph.settings.lastDeckFor(deckKey), t.byId.values.toList(), source.language, graph.settings.lastDeck)
        }
    }

    fun add(more: Boolean) {
        val deck = deckId ?: return
        val c = content
        val a = answer.trim()
        val q = question.trim()
        val picture = c?.picture?.takeIf { attach }
        graph.settings.setLastDeckFor(deckKey, deck)
        val app = context.applicationContext
        graph.appScope.launch {
            runCatching {
                val mode = if (WritingLayout.forAnswer(a) is WritingLayout.Cells) AnswerMode.HANDWRITING else AnswerMode.CLASSIC
                val back = if (picture == null) a else {
                    val name = "card-${UUID.randomUUID()}.png"
                    val png = withContext(Dispatchers.Default) {
                        ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    }
                    graph.media.save(name, png.inputStream())
                    if (a.isEmpty()) "<img src=\"$name\">" else "$a<br><img src=\"$name\">"
                }
                val b = c?.bounds
                val ref = SourceRefEntity(
                    noteId = "", kind = source.kind.name, sourceId = source.sourceId,
                    x = b?.left, y = b?.top, w = b?.let { it.right - it.left }, h = b?.let { it.bottom - it.top },
                    linked = false, createdAt = System.currentTimeMillis(),
                )
                graph.repository.addCardFromSource(deck, q, back, mode, ref)
            }.onFailure {
                Log.e("Wanki", "card from selection not saved", it)
                ui { Toast.makeText(app, "Не удалось сохранить карточку", Toast.LENGTH_LONG).show() }
            }
        }
        if (more) {
            note = "Карточка добавлена"
            generation++
        } else onDismiss()
    }

    val canAdd = !loading && deckId != null && question.isNotBlank() && (answer.isNotBlank() || (attach && content?.picture != null))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) {
                    Text("Распознаю…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        answer, { answer = it; note = null }, label = { Text("Ответ") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("card-answer"),
                    )
                    OutlinedTextField(
                        question, { question = it; note = null }, label = { Text("Вопрос") },
                        modifier = Modifier.fillMaxWidth().testTag("card-question"),
                    )
                    if (content?.picture != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(attach, { attach = it }, Modifier.testTag("card-attach"))
                        Text("Приложить рукопись")
                    }
                }
                FlowRow {
                    AssistChip(
                        onClick = { picking = true }, enabled = tree != null,
                        label = { Text("Колода: " + (deckId?.let { tree?.title(it) } ?: "выбрать"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("card-deck"),
                    )
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("card-close")) { Text("Закрыть") }
                TextButton(enabled = canAdd, onClick = { add(true) }, modifier = Modifier.testTag("card-add-more")) { Text("Добавить ещё") }
                TextButton(enabled = canAdd, onClick = { add(false) }, modifier = Modifier.testTag("card-add")) { Text("Добавить") }
            }
        },
    )
    val t = tree
    if (picking && t != null) {
        DeckPickerDialog(t, "Колода", deckId, onPick = { d -> if (d != null) deckId = d.id; picking = false }, onDismiss = { picking = false })
    }
}
