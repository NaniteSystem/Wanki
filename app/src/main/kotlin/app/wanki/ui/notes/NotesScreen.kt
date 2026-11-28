package app.wanki.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.data.NoteBlockEntity
import app.wanki.data.NoteBlockType
import app.wanki.data.PageTemplate
import app.wanki.data.TextNoteEntity
import app.wanki.data.TrashKind
import app.wanki.data.newId
import app.wanki.graph
import app.wanki.ink.InkCodec
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.input.EraserSettings
import app.wanki.input.InkLayer
import app.wanki.input.InkSurface
import app.wanki.input.ToolKind
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.recognition.AUTO_LANGUAGE
import app.wanki.ui.common.InkToolbar
import app.wanki.ui.common.SaveErrorSnackbar
import app.wanki.ui.common.SerialSaver
import app.wanki.R
import app.wanki.data.ListOrder
import app.wanki.data.Settings.Companion.SECTION_NOTES
import app.wanki.ui.common.ListActions
import app.wanki.ui.common.ListCard
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.RenameDialog
import app.wanki.ui.common.itemMenu
import app.wanki.ui.common.sortMenu
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.ListState
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.drawTemplate
import app.wanki.ui.common.finalSave
import app.wanki.ui.common.rememberGuardedBack
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.common.ui
import app.wanki.ui.texts.RecordKind
import app.wanki.ui.texts.RecordRef
import app.wanki.ui.texts.recordText
import app.wanki.ui.texts.recordTextProblem
import app.wanki.writing.WritingPaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun NotesScreen(onOpen: (String?) -> Unit) {
    val graph = LocalContext.current.graph
    val notes by remember { graph.repository.observeTextNotes() }.collectAsState(null)
    var query by remember { mutableStateOf("") }
    val sort = graph.settings.listSort(SECTION_NOTES)
    val shown = remember(notes, query, sort) {
        val found = if (query.isBlank()) notes.orEmpty() else notes.orEmpty().filter {
            it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
        }
        ListOrder.sort(found, sort, { it.pinned }, { it.title }, { it.updatedAt }, { it.createdAt })
    }
    val undo = LocalUndo.current
    val actions = remember { ListActions(graph.repository, undo, graph.appScope) }
    var renaming by remember { mutableStateOf<TextNoteEntity?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Заметки", menu = sortMenu(graph.settings, SECTION_NOTES))
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Поиск") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ListState(
                loading = notes == null, isEmpty = notes.orEmpty().isEmpty(),
                emptyTitle = "Пока нет заметок", emptyHint = "Текст и рукописные блоки вперемешку",
                emptyAction = HeaderAction(R.drawable.ic_sticky_note_2, "Новая заметка", "notes-empty-new") { onOpen(null) },
            ) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.id }) { n ->
                    ListCard(
                        "note-${n.id}", n.pinned,
                        itemMenu(
                            n.pinned,
                            onRename = { renaming = n },
                            onDuplicate = { actions.duplicateNote(n.id) },
                            onPin = { actions.pin(TrashKind.NOTE, n.id, !n.pinned) },
                            onDelete = { undo.deleted(TrashKind.NOTE, n.id) },
                        ),
                        onClick = { onOpen(n.id) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(end = 24.dp))
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
        }
        if (!notes.isNullOrEmpty()) ExtendedFloatingActionButton(onClick = { onOpen(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Заметка")
        }
    }
    renaming?.let { n ->
        RenameDialog(n.title, onDismiss = { renaming = null }) { actions.renameNote(n.id, it); renaming = null }
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

/**
 * Note editor: text and handwriting blocks mixed freely; saves and recognizes automatically.
 * A new note ([id] null) reports its id through [onCreated] once it is first saved, so the route
 * can carry it (a restored screen then reopens the note instead of a blank one).
 */
@Composable
fun NoteEditorScreen(id: String?, onBack: () -> Unit, onCreated: (String) -> Unit = {}, onMemorize: (String) -> Unit = {}) {
    val context = LocalContext.current
    val graph = context.graph
    val undo = LocalUndo.current
    val scope = rememberUiScope()
    var memorizing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<TextNoteEntity?>(null) }
    // the note is in the database: from then on an emptied note is saved empty, not skipped
    var stored by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    val blocks = remember { mutableStateListOf<BlockState>() }
    val changes = remember { NoteChanges() }
    var tool by remember { mutableStateOf(graph.settings.tool(InkSection.NOTE)) }
    var eraser by remember { mutableStateOf(graph.settings.eraser(InkSection.NOTE)) }
    var activeBlockId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        // the route of a new note was just given its id: the note on screen is that note already
        if (id != null && note?.id == id) return@LaunchedEffect
        ui {
            val found = id?.let { graph.repository.textNote(it) }
            val n = found ?: TextNoteEntity(title = "", body = "")
            stored = found != null
            val rows = graph.repository.noteBlocks(n.id)
            blocks.clear()
            if (rows.isEmpty()) {
                // notes from before blocks existed: their body becomes the first text block
                blocks += BlockState(newId(), NoteBlockType.TEXT, n.body)
            } else rows.forEach { b ->
                val strokes = b.strokes?.let { runCatching { InkCodec.decode(it).strokes }.getOrNull() }.orEmpty()
                blocks += BlockState(b.id, runCatching { NoteBlockType.valueOf(b.type) }.getOrDefault(NoteBlockType.TEXT), b.text, strokes, b.heightDp, b.recognizedText)
            }
            title = n.title
            note = n
            changes.loaded(blocks)
        }
    }
    val inkVersions = blocks.sumOf { it.layer?.version ?: 0 }
    suspend fun save() {
        val n = note ?: return
        // the snapshot is taken first: changes made while this save runs stay unsaved
        val v = changes.unsaved(blocks) ?: return
        // a blank new note is not created; a stored one the user emptied is saved empty
        if (!stored && title.isBlank() && blocks.all { it.text.isBlank() && (it.layer?.isEmpty != false) }) return
        graph.repository.saveNoteWithBlocks(n.copy(title = title), blocks.map { it.toEntity(n.id) })
        changes.markSaved(v)
        stored = true
    }
    LaunchedEffect(stored) { if (stored && id == null) note?.let { onCreated(it.id) } }
    val latestSave by rememberUpdatedState<suspend () -> Unit> { save() }
    val saver = remember { SerialSaver(graph.appScope) { latestSave() } }
    // every text change and every finished ink gesture is saved; recognition waits for a pause
    LaunchedEffect(changes.edits, inkVersions) {
        if (note == null || changes.unsaved(blocks) == null) return@LaunchedEffect
        saver.request()
        // recognize handwriting blocks after a longer pause so search finds them
        delay(2500)
        var changed = false
        for (b in blocks.toList()) {
            val l = b.layer ?: continue
            val r = ui { graph.textRecognition.recognize(l.strokes.toList(), AUTO_LANGUAGE) } ?: continue
            if (r.text != b.recognizedText) { b.recognizedText = r.text; changed = true }
        }
        if (changed) {
            changes.recognized()
            saver.request()
        }
    }
    // «Учить наизусть»: the note's text (handwriting read now, so a line written a moment ago counts) goes to a new text
    fun memorize() {
        val n = note ?: return
        if (memorizing) return
        memorizing = true
        scope.launch {
            try {
                var changed = false
                for (b in blocks.toList()) {
                    val l = b.layer?.takeIf { !it.isEmpty } ?: continue
                    val r = graph.textRecognition.recognize(l.strokes.toList(), AUTO_LANGUAGE) ?: continue
                    if (r.text != b.recognizedText) { b.recognizedText = r.text; changed = true }
                }
                if (changed) changes.recognized()
                // the editor reads the stored note
                saver.request()
                saver.flush()
                if (saver.error.value != null) { undo.say("Заметка не сохранилась — сначала сохраните её"); return@launch }
                val r = recordText(graph.repository, RecordRef(RecordKind.NOTE, n.id), hasInk = blocks.any { it.layer?.isEmpty == false })
                val problem = recordTextProblem(r)
                if (problem != null) undo.say(problem) else onMemorize(n.id)
            } finally {
                memorizing = false
            }
        }
    }
    // leaving while saves fail asks first; leaving any other way still saves once more and reports a failure
    val back = rememberGuardedBack(saver, onBack)
    DisposableEffect(Unit) {
        onDispose {
            graph.appScope.launch { saver.finalSave(context.applicationContext, "note ${note?.id ?: id}") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title.ifBlank { "Заметка" }, onBack = back,
                menu = if (id == null) emptyList() else listOf(
                    HeaderAction(R.drawable.ic_article, "Учить наизусть", "menu-memorize", enabled = !memorizing, onClick = ::memorize),
                    HeaderAction(R.drawable.ic_delete, "Удалить", "menu-delete") {
                        note = null
                        // a save still running must not bring the note back after it is deleted
                        graph.appScope.launch { saver.flush(); undo.deleted(TrashKind.NOTE, id) }
                        onBack()
                    },
                ),
            )
            if (blocks.any { it.type == NoteBlockType.INK }) {
                // undo/redo act on the handwriting block touched last
                val active = blocks.firstOrNull { it.id == activeBlockId && it.layer != null } ?: blocks.first { it.layer != null }
                InkToolbar(tool, { tool = it; graph.settings.setTool(InkSection.NOTE, it) }, active.layer!!,
                    eraser, { eraser = it; graph.settings.setEraser(InkSection.NOTE, it) }, Modifier.padding(horizontal = 8.dp),
                    tools = listOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.HIGHLIGHTER, ToolKind.ERASER))
            }
            val colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                TextField(
                    title, { title = it; changes.edit() }, placeholder = { Text("Заголовок") },
                    textStyle = MaterialTheme.typography.headlineSmall, colors = colors, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                blocks.forEachIndexed { i, b ->
                    when (b.type) {
                        NoteBlockType.TEXT -> TextField(
                            b.text, { b.text = it; changes.edit() }, placeholder = { Text("Текст") },
                            colors = colors, modifier = Modifier.fillMaxWidth(),
                        )
                        NoteBlockType.INK -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp), color = WritingPaper,
                                modifier = Modifier.fillMaxWidth().height(b.heightDp.dp).padding(vertical = 4.dp)
                                    .pointerInput(b.id) {
                                        // only watches: the touch still reaches the canvas
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            activeBlockId = b.id
                                        }
                                    },
                            ) {
                                InkSurface(
                                    layer = b.layer!!, tool = tool, modifier = Modifier.fillMaxSize(), gestures = true, eraser = eraser,
                                    background = { drawTemplate(PageTemplate.LINED, InkRect(0f, 0f, size.width, size.height)) },
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (b.recognizedText.isNotBlank()) {
                                    Text(b.recognizedText, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                } else Spacer(Modifier.weight(1f))
                                TextButton(onClick = { b.heightDp += 200f; changes.edit() }) { Text("Выше") }
                                TextButton(onClick = { changes.removed(b); blocks.removeAt(i) }) { Text("Убрать") }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { blocks += BlockState(newId(), NoteBlockType.TEXT); changes.edit() }) { Text("+ Текст") }
                    OutlinedButton(onClick = { blocks += BlockState(newId(), NoteBlockType.INK); changes.edit() }) { Text("+ От руки") }
                }
                Spacer(Modifier.height(120.dp))
            }
        }
        SaveErrorSnackbar(saver, Modifier.align(Alignment.BottomCenter))
    }
}
