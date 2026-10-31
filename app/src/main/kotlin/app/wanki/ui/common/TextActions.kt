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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.wanki.R
import app.wanki.input.InkClipboard
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
import app.wanki.data.PageObjectKind
import app.wanki.recognition.TranslationService
import app.wanki.review.WritingLayout
import kotlinx.coroutines.launch

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Wanki", text))
}

/**
 * Bar shown while handwriting or page objects are selected with the lasso: the count, recognize → text actions (copy,
 * translate, make a card; the text of selected text blocks when no strokes are selected), delete, and «⋮» with
 * «Изменить текст» (exactly one text block selected, [onEditText]), copy, duplicate, colour, (notebooks only,
 * [onMoveToPage]) «На другую страницу…» and «Снять выделение». The count is a bare number so the bar fits a 360 dp
 * phone at font scale 1.3.
 */
@Composable
fun SelectionBar(
    layer: InkLayer,
    language: String,
    modifier: Modifier = Modifier,
    onMoveToPage: (() -> Unit)? = null,
    onEditText: ((String) -> Unit)? = null,
) {
    val graph = LocalContext.current.graph
    var dialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    val scope = rememberUiScope()
    val count = layer.selection.size + layer.selectedObjects.size
    val texts = layer.selectedObjectList().filter { it.kind == PageObjectKind.TEXT }
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                count.toString(), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "Выделено: $count" }.testTag("sel-count"),
            )
            TextButton(onClick = {
                dialog = true
                text = null
                failed = false
                val strokes = layer.selection.toList()
                if (strokes.isEmpty()) text = texts.joinToString("\n") { it.text }
                else scope.launch {
                    val r = graph.textRecognition.recognize(strokes, language)
                    if (r == null) failed = true else text = r.text
                }
            }, modifier = Modifier.weight(1f, fill = false).testTag("sel-text")) { Text("Текст и перевод", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = layer::deleteSelection, modifier = Modifier.testTag("sel-delete")) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Удалить")
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("sel-more")) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Ещё")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onEditText != null && texts.size == 1) SelectionMenuItem("Изменить текст", R.drawable.ic_edit, "sel-edit-text") {
                        menu = false
                        onEditText(texts.single().id)
                    }
                    SelectionMenuItem("Копировать", R.drawable.ic_content_copy, "sel-copy") {
                        menu = false
                        InkClipboard.clip = layer.copySelection()
                    }
                    SelectionMenuItem("Дублировать", R.drawable.ic_add, "sel-duplicate") { menu = false; layer.duplicateSelection() }
                    SelectionMenuItem("Цвет…", R.drawable.ic_palette, "sel-color") { menu = false; coloring = true }
                    if (onMoveToPage != null) SelectionMenuItem("На другую страницу…", R.drawable.ic_move_item, "sel-move-page") {
                        menu = false
                        onMoveToPage()
                    }
                    SelectionMenuItem("Снять выделение", null, "sel-clear") { menu = false; layer.clearSelection() }
                }
            }
        }
    }
    if (dialog) {
        TextActionsDialog(
            initialText = text,
            failed = failed,
            onDismiss = { dialog = false },
        )
    }
    if (coloring) ColorPickDialog(onPick = { c -> coloring = false; layer.recolorSelection(c) }, onDismiss = { coloring = false })
}

@Composable
private fun SelectionMenuItem(label: String, icon: Int?, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    )
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
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    var f by remember { mutableStateOf(front) }
    var b by remember { mutableStateOf(back) }
    var deckId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая карточка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(f, { f = it }, label = { Text("Вопрос") })
                OutlinedTextField(b, { b = it }, label = { Text("Ответ") })
                OutlinedButton(onClick = { picking = true }, enabled = tree != null) {
                    Text("Колода: " + (deckId?.let { id -> tree?.title(id) } ?: "выбрать"))
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
    val t = tree
    if (picking && t != null) {
        DeckPickerDialog(t, "Колода", deckId, onPick = { d -> if (d != null) deckId = d.id; picking = false }, onDismiss = { picking = false })
    }
}
