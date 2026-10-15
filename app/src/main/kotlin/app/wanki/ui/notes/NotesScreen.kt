package app.wanki.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.data.TextNoteEntity
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun NotesScreen(onOpen: (String?) -> Unit) {
    val graph = LocalContext.current.graph
    val notes by remember { graph.repository.observeTextNotes() }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) notes else notes.filter {
        it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Заметки")
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Поиск") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(n.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(n.body, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onOpen(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Заметка")
        }
    }
}

/** Text note editor; saves automatically. */
@Composable
fun NoteEditorScreen(id: String?, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    var note by remember { mutableStateOf<TextNoteEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val n = id?.let { graph.repository.textNote(it) } ?: TextNoteEntity(title = "", body = "")
        note = n
        title = n.title
        body = n.body
    }
    LaunchedEffect(title, body) {
        if (!dirty) return@LaunchedEffect
        delay(600)
        note?.let { graph.repository.saveTextNote(it.copy(title = title, body = body)) }
        dirty = false
    }
    DisposableEffect(Unit) {
        onDispose {
            val n = note
            if (n != null && (title.isNotBlank() || body.isNotBlank()) && dirty) {
                graph.appScope.launch { graph.repository.saveTextNote(n.copy(title = title, body = body)) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Заметка", onBack = onBack) {
            if (id != null) {
                TextButton(onClick = {
                    graph.appScope.launch { graph.repository.deleteTextNote(id) }
                    dirty = false
                    onBack()
                }) { Text("Удалить") }
            }
        }
        val colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        )
        TextField(
            title, { title = it; dirty = true }, placeholder = { Text("Заголовок") },
            textStyle = MaterialTheme.typography.headlineSmall, colors = colors, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        TextField(
            body, { body = it; dirty = true }, placeholder = { Text("Текст заметки") },
            colors = colors, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        )
    }
}
