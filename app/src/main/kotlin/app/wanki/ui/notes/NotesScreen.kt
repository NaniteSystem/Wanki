package app.wanki.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import app.wanki.data.NoteBlockEntity
import app.wanki.data.NoteBlockType
import app.wanki.data.PageTemplate
import app.wanki.data.newId
import app.wanki.ink.InkCodec
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.ToolKind
import app.wanki.input.ToolSelection
import app.wanki.recognition.AUTO_LANGUAGE
import app.wanki.ui.common.InkToolbar
import app.wanki.ui.common.drawTemplate
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

/** One block of the note being edited. */
class BlockState(
    val id: String,
    val type: NoteBlockType,
    text: String = "",
    strokes: List<InkStroke> = emptyList(),
    heightDp: Float = 320f,
    var recognizedText: String = "",
) {
    var text by mutableStateOf(text)
    var heightDp by mutableFloatStateOf(heightDp)
    val layer: InkLayer? = if (type == NoteBlockType.INK) InkLayer(strokes) else null

    fun toEntity(noteId: String) = NoteBlockEntity(
        id = id, noteId = noteId, blockIndex = 0, type = type.name, text = text,
        strokes = layer?.let { InkCodec.encode(it.drawing()) }, heightDp = heightDp, recognizedText = recognizedText,
    )
}

/** Note editor: text and handwriting blocks mixed freely; saves and recognizes automatically. */
@Composable
fun NoteEditorScreen(id: String?, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    var note by remember { mutableStateOf<TextNoteEntity?>(null) }
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<BlockState>() }
    var version by remember { mutableIntStateOf(0) }
    var savedVersion by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(ToolSelection()) }

    LaunchedEffect(id) {
        val n = id?.let { graph.repository.textNote(it) } ?: TextNoteEntity(title = "", body = "")
        val stored = graph.repository.noteBlocks(n.id)
        blocks.clear()
        if (stored.isEmpty()) {
            // notes from before blocks existed: their body becomes the first text block
            blocks += BlockState(newId(), NoteBlockType.TEXT, n.body)
        } else stored.forEach { b ->
            val strokes = b.strokes?.let { runCatching { InkCodec.decode(it).strokes }.getOrNull() }.orEmpty()
            blocks += BlockState(b.id, runCatching { NoteBlockType.valueOf(b.type) }.getOrDefault(NoteBlockType.TEXT), b.text, strokes, b.heightDp, b.recognizedText)
        }
        title = n.title
        note = n
    }
    val inkVersions = blocks.sumOf { it.layer?.version ?: 0 }
    suspend fun save() {
        val n = note ?: return
        if (title.isBlank() && blocks.all { it.text.isBlank() && (it.layer?.isEmpty != false) }) return
        graph.repository.saveNoteWithBlocks(n.copy(title = title), blocks.map { it.toEntity(n.id) })
        savedVersion = version + inkVersions
    }
    LaunchedEffect(version, inkVersions) {
        if (note == null || version + inkVersions == savedVersion) return@LaunchedEffect
        delay(700)
        save()
        // recognize handwriting blocks after a longer pause so search finds them
        delay(2500)
        var changed = false
        for (b in blocks.toList()) {
            val l = b.layer ?: continue
            val r = graph.textRecognition.recognize(l.strokes.toList(), AUTO_LANGUAGE) ?: continue
            if (r.text != b.recognizedText) { b.recognizedText = r.text; changed = true }
        }
        if (changed) save()
    }
    DisposableEffect(Unit) {
        onDispose { graph.appScope.launch { save() } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Заметка", onBack = onBack) {
            if (id != null) {
                TextButton(onClick = {
                    graph.appScope.launch { graph.repository.deleteTextNote(id) }
                    note = null
                    onBack()
                }) { Text("Удалить") }
            }
        }
        if (blocks.any { it.type == NoteBlockType.INK }) {
            InkToolbar(tool, { tool = it }, blocks.firstNotNullOf { it.layer }, Modifier.padding(horizontal = 8.dp),
                tools = listOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.HIGHLIGHTER, ToolKind.ERASER))
        }
        val colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            TextField(
                title, { title = it; version++ }, placeholder = { Text("Заголовок") },
                textStyle = MaterialTheme.typography.headlineSmall, colors = colors, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            blocks.forEachIndexed { i, b ->
                when (b.type) {
                    NoteBlockType.TEXT -> TextField(
                        b.text, { b.text = it; version++ }, placeholder = { Text("Текст") },
                        colors = colors, modifier = Modifier.fillMaxWidth(),
                    )
                    NoteBlockType.INK -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().height(b.heightDp.dp).padding(vertical = 4.dp),
                        ) {
                            InkSurface(
                                layer = b.layer!!, tool = tool, modifier = Modifier.fillMaxSize(),
                                background = { drawTemplate(PageTemplate.LINED, InkRect(0f, 0f, size.width, size.height)) },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (b.recognizedText.isNotBlank()) {
                                Text(b.recognizedText, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            } else Spacer(Modifier.weight(1f))
                            TextButton(onClick = { b.heightDp += 200f; version++ }) { Text("Выше") }
                            TextButton(onClick = { blocks.removeAt(i); version++ }) { Text("Убрать") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { blocks += BlockState(newId(), NoteBlockType.TEXT); version++ }) { Text("+ Текст") }
                OutlinedButton(onClick = { blocks += BlockState(newId(), NoteBlockType.INK); version++ }) { Text("+ От руки") }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}
