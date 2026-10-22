package app.wanki.ui.texts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.data.TextRepository
import app.wanki.graph
import app.wanki.texts.PieceWords
import app.wanki.texts.ReadingOverride
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import kotlinx.coroutines.launch

/** One unique word of the text: what is typed for it and the other spellings accepted. */
private data class ReadingRow(val surface: String, val reading: String, val forms: List<String>, val custom: Boolean)

/** The words of a Japanese text with their readings; tapping one edits the text's reading for that written form. */
@Composable
fun TextReadingsScreen(id: String, onBack: () -> Unit, texts: TextRepository = LocalContext.current.graph.texts) {
    val text by remember(id) { texts.observeText(id) }.collectAsState(null)
    val pieces by remember(id) { texts.observePieces(id) }.collectAsState(null)
    val rows by produceState<List<ReadingRow>?>(null, text?.readings, pieces) {
        val t = text
        val ps = pieces
        if (t == null || ps == null) return@produceState
        val overrides = PieceWords.decodeReadings(t.readings)
        val words = ui { texts.words(ps) }
        val seen = LinkedHashMap<String, ReadingRow>()
        for (p in ps) for (w in words[p.id].orEmpty()) {
            val surface = p.content.substring(w.start, w.end)
            if (surface !in seen) seen[surface] = ReadingRow(surface, w.reading, w.forms, surface in overrides)
        }
        value = seen.values.toList()
    }
    // setReadings recomputes the words of every piece: it must outlive the dialog but not the screen
    val scope = rememberUiScope()
    var editing by remember { mutableStateOf<ReadingRow?>(null) }

    fun store(change: (Map<String, ReadingOverride>) -> Map<String, ReadingOverride>) {
        val t = text ?: return
        scope.launch { texts.setReadings(id, change(PieceWords.decodeReadings(t.readings))) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Чтения", onBack = onBack, subtitle = text?.title, subtitleMaxLines = 1)
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> Text("В тексте нет слов", modifier = Modifier.padding(16.dp))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp).testTag("readings-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        Text(
                            "Нажмите на слово, чтобы изменить, что набирать для него. Изменённые чтения помечены.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(list, key = { it.surface }) { r ->
                        Card(Modifier.fillMaxWidth().clickable { editing = r }.testTag("reading-${r.surface}")) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(r.surface, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    r.reading + if (r.custom) " · изменено" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (r.forms.isNotEmpty()) {
                                    Text(
                                        "Также: " + r.forms.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        var reading by remember(r) { mutableStateOf(r.reading) }
        var forms by remember(r) { mutableStateOf(r.forms.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(r.surface, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        reading, { reading = it }, label = { Text("Чтение") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("reading-field"),
                    )
                    OutlinedTextField(
                        forms, { forms = it }, label = { Text("Другие написания (через запятую)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("forms-field"),
                    )
                    if (r.custom) {
                        TextButton(onClick = { editing = null; store { it - r.surface } }, modifier = Modifier.testTag("reading-reset")) {
                            Text("Сбросить")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reading.isNotBlank(),
                    onClick = {
                        val o = ReadingOverride(reading.trim(), parseForms(forms, r.surface))
                        editing = null
                        store { it + (r.surface to o) }
                    },
                    modifier = Modifier.testTag("reading-save"),
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
}

/** The comma-separated spellings, without blanks, repeats or the written form itself. */
internal fun parseForms(s: String, surface: String): List<String> =
    s.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() && it != surface }.distinct()
