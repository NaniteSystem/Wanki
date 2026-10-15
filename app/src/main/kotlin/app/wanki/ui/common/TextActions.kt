package app.wanki.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.data.AnswerMode
import app.wanki.graph
import app.wanki.input.InkLayer
import app.wanki.recognition.TranslationService
import app.wanki.review.WritingLayout
import kotlinx.coroutines.launch

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Wanki", text))
}

/**
 * Bar shown while handwriting is selected with the lasso: recognize → text actions
 * (copy, translate, make a card), or delete the strokes.
 */
@Composable
fun SelectionBar(layer: InkLayer, language: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    var dialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberUiScope()
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Выделено: ${layer.selection.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = {
                dialog = true
                text = null
                failed = false
                val strokes = layer.selection.toList()
                scope.launch {
                    val r = graph.textRecognition.recognize(strokes, language)
                    if (r == null) failed = true else text = r.text
                }
            }) { Text("Текст и перевод") }
            TextButton(onClick = layer::deleteSelection) { Text("Удалить") }
            TextButton(onClick = layer::clearSelection) { Text("✕") }
        }
    }
    if (dialog) {
        TextActionsDialog(
            initialText = text,
            failed = failed,
            onDismiss = { dialog = false },
        )
    }
}

/** Recognized (editable) text with copy, translate and "make a card". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextActionsDialog(initialText: String?, failed: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberUiScope()
    var text by remember(initialText) { mutableStateOf(initialText.orEmpty()) }
    var target by remember { mutableStateOf(graph.settings.translateTo) }
    var translating by remember { mutableStateOf(false) }
    var translation by remember { mutableStateOf<TranslationService.Result?>(null) }
    var makingCard by remember { mutableStateOf(false) }

    fun translate() {
        translating = true
        scope.launch {
            translation = graph.translation.translate(text, target)
            translating = false
        }
    }
    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) translate() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Текст") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    failed -> Text("Распознавание пока недоступно: модель ещё загружается или нет сети.")
                    initialText == null -> { Text("Распознаю…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> OutlinedTextField(
                        text, { text = it; translation = null },
                        label = { Text("Распознано (можно исправить)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    )
                }
                if (initialText != null) {
                    Text("Перевод на:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TranslateTargets.forEach { (code, name) ->
                            FilterChip(target == code, {
                                target = code
                                graph.settings.translateTo = code
                                translate()
                            }, { Text(name) })
                        }
                    }
                    when (val t = translation) {
                        null -> if (translating) Text("Перевожу… (при первом переводе загружается модель)") else
                            OutlinedButton(onClick = ::translate, enabled = text.isNotBlank()) { Text("Перевести") }
                        is TranslationService.Result.Ok -> SelectionContainer {
                            Text(t.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        is TranslationService.Result.Failed -> Text(t.reason, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = text.isNotBlank(), onClick = { copyToClipboard(context, text) }) { Text("Копировать") }
                TextButton(enabled = text.isNotBlank(), onClick = { makingCard = true }) { Text("В карточку") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
    if (makingCard) {
        val translated = (translation as? TranslationService.Result.Ok)?.text
        MakeCardDialog(front = translated ?: "", back = text, onDismiss = { makingCard = false })
    }
}

/** Creates a card from a piece of handwriting: front = translation, back = the written text. */
@Composable
fun MakeCardDialog(front: String, back: String, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val decks by remember { graph.repository.observeDecks() }.collectAsState(emptyList())
    var f by remember { mutableStateOf(front) }
    var b by remember { mutableStateOf(back) }
    var deckId by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(f, { f = it }, label = { Text("Вопрос") })
                OutlinedTextField(b, { b = it }, label = { Text("Ответ") })
                Text("Колода", style = MaterialTheme.typography.labelLarge)
                decks.forEach { d ->
                    FilterChip(deckId == d.deck.id, { deckId = d.deck.id }, { Text(d.deck.name) })
                }
                if (done) Text("Карточка добавлена", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = f.isNotBlank() && b.isNotBlank() && deckId != null && !done,
                onClick = {
                    val mode = if (WritingLayout.forAnswer(b) is WritingLayout.Cells) AnswerMode.HANDWRITING else AnswerMode.CLASSIC
                    graph.appScope.launch { graph.repository.addCard(deckId!!, f, b, mode) }
                    done = true
                },
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
