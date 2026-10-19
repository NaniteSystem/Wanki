package app.wanki.ui.texts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wanki.data.TextEntity
import app.wanki.graph
import app.wanki.texts.TextKind
import app.wanki.texts.TextLoad
import app.wanki.texts.TextSchedule
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.Languages
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TextEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val state = remember(id) { TextEditorState() }
    var original by remember(id) { mutableStateOf<TextEntity?>(null) }
    var loaded by remember(id) { mutableStateOf(id == null) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        val t = ui { graph.texts.text(id) }
        if (t != null) state.load(t, ui { graph.texts.pieces(id) })
        original = t
        loaded = true
    }
    // the language follows the text until the user picks one
    LaunchedEffect(state.content, state.languagePicked) {
        if (state.languagePicked || !PieceDrafts.hasContent(state.content)) return@LaunchedEffect
        delay(500)
        ui { graph.textRecognition.identify(state.content.take(2000)) }?.let(state::detected)
    }

    fun save() {
        val pieces = state.pieces()
        // a text never has zero pieces (saveText would keep an empty list as "pieces unchanged")
        if (saving || pieces.isEmpty()) return
        saving = true
        val title = state.resolvedTitle()
        val detect = if (state.languagePicked) null else state.content.take(2000)
        val picked = state.language
        // a new text is one saveText call too: it stores the text and its pieces in one transaction
        val base = original ?: TextEntity(title = title, language = picked)
        val kind = state.kind
        val schedule = state.schedule
        val portion = state.portion
        graph.appScope.launch {
            // saved right after pasting: the debounced detection may not have run yet
            val language = detect?.let { graph.textRecognition.identify(it) } ?: picked
            graph.texts.saveText(
                base.copy(title = title, language = language, kind = kind.name, schedule = schedule.name, portionSize = portion),
                pieces,
            )
            saving = false
            onSaved(base.id)
        }
    }

    BackHandler(enabled = state.changed) { leaving = true }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (id == null) "Новый текст" else "Изменить текст", onBack = { if (state.changed) leaving = true else onBack() }) {
            Button(
                onClick = ::save,
                enabled = loaded && !saving && state.canSave,
                modifier = Modifier.padding(end = 8.dp).testTag("save-text"),
            ) { Text("Сохранить") }
        }
        if (!loaded) return@Column
        val sabqiDays = graph.settings.sabqiDays
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TextSettings(state, sabqiDays)
                        ContentField(state)
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pieceList(state)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { TextSettings(state, sabqiDays) }
                    item { ContentField(state, Modifier.padding(top = 8.dp)) }
                    pieceList(state)
                }
            }
        }
    }
    if (leaving) {
        ConfirmDialog("Выйти без сохранения?", "Изменения пропадут.", "Выйти", onConfirm = onBack, onDismiss = { leaving = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSettings(state: TextEditorState, sabqiDays: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            state.title, { state.title = it },
            label = { Text("Название") },
            placeholder = { Text("По первой строке") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Label("Язык" + if (!state.languagePicked && state.languageDetected) " (определён по тексту)" else "")
        val offered = Languages.map { it.first }.let { if (state.language in it) it else it + state.language }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            offered.forEach { code -> FilterChip(state.language == code, { state.pickLanguage(code) }, { Text(textLanguageName(code)) }) }
        }
        Label("Как делить")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.kind == TextKind.POEM, { state.changeKind(TextKind.POEM) }, { Text("Стих") })
            FilterChip(state.kind == TextKind.PROSE, { state.changeKind(TextKind.PROSE) }, { Text("Проза") })
        }
        Hint(if (state.kind == TextKind.POEM) "Каждая строка — отдельный кусок." else "Каждое предложение — отдельный кусок.")
        Label("Расписание")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextSchedule.entries.forEach { s -> FilterChip(state.schedule == s, { state.changeSchedule(s) }, { Text(scheduleName(s)) }) }
        }
        Hint(
            if (state.schedule == TextSchedule.FSRS) "Каждый выученный кусок повторяется, когда подходит его срок."
            else "Сабак — новая порция, сабки — недавно выученное каждый день, манзиль — всё старое по кругу.",
        )
        Label("Порция — новых кусков в день")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedIconButton(onClick = { state.changePortion(state.portion - 1) }, enabled = state.portion > 1) { Text("−") }
            Text("${state.portion}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 32.dp))
            OutlinedIconButton(onClick = { state.changePortion(state.portion + 1) }, enabled = state.portion < 20) { Text("+") }
            Text(
                "≈ +${TextLoad.daily(state.schedule, state.portion, sabqiDays)} в день",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContentField(state: TextEditorState, modifier: Modifier = Modifier) {
    Directional(state.language) {
        OutlinedTextField(
            state.content, state::editContent,
            label = { Text("Текст") },
            placeholder = { Text("Вставьте или наберите текст") },
            textStyle = pieceStyle(state.language),
            minLines = 5,
            maxLines = 14,
            modifier = modifier.fillMaxWidth().testTag("text-content"),
        )
    }
}

private fun LazyListScope.pieceList(state: TextEditorState) {
    item {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Куски: ${state.pieces().size}", style = MaterialTheme.typography.titleMedium)
            Hint(
                when {
                    state.drafts.isEmpty() && state.content.isBlank() -> "Куски появятся здесь, когда вы наберёте текст."
                    !state.canSave -> "В тексте нет ни букв, ни цифр — сохранять нечего."
                    else -> "Каждый кусок учится и повторяется отдельно. Чтобы разрезать кусок, нажмите в нём там, где резать, " +
                        "и «Разрезать» (или Enter)."
                },
            )
        }
    }
    itemsIndexed(state.drafts, key = { _, d -> d.key }) { i, d ->
        PieceEditor(
            i, d, state.language,
            canSplit = state.canSplit(i),
            onEdit = { state.editPiece(i, it) },
            onJoin = if (i > 0) ({ state.joinWithPrevious(i) }) else null,
            onSplit = { state.split(i) },
            onOpenNote = { state.openNote(i) },
            onNote = { state.editNote(i, it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceEditor(
    index: Int, d: Draft, language: String, canSplit: Boolean,
    onEdit: (TextFieldValue) -> Unit,
    onJoin: (() -> Unit)?, onSplit: () -> Unit, onOpenNote: () -> Unit, onNote: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
            // mirrored for RTL, so the number stands where the line starts
            Directional(language) {
                Row {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(28.dp).padding(top = 18.dp, end = 6.dp),
                    )
                    OutlinedTextField(d.value, onEdit, textStyle = pieceStyle(language), modifier = Modifier.weight(1f))
                }
            }
            FlowRow(Modifier.padding(start = 28.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onJoin != null) TextButton(onClick = onJoin) { Text("Склеить с предыдущим") }
                TextButton(onClick = onSplit, enabled = canSplit) { Text("Разрезать") }
                if (!d.noteOpen) TextButton(onClick = onOpenNote) { Text("Перевод/пояснение") }
            }
            if (d.noteOpen) {
                OutlinedTextField(
                    d.note, onNote,
                    label = { Text("Перевод/пояснение") },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
