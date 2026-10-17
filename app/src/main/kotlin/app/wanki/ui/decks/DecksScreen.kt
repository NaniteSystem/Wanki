package app.wanki.ui.decks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wanki.data.AnswerMode
import app.wanki.data.DeckEntity
import app.wanki.data.DeckRow
import app.wanki.data.DeckTree
import app.wanki.graph
import app.wanki.importer.AnkiImporter
import app.wanki.importer.TextImporter
import app.wanki.practice.CharQuality
import app.wanki.ui.common.DeckPickerDialog
import app.wanki.ui.common.Languages
import app.wanki.ui.common.MenuButton
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.languageName
import app.wanki.ui.common.ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val NewColor = Color(0xFF1E5BD8)
val LearnColor = Color(0xFFC0392B)
val DueColor = Color(0xFF2E9E5B)

@Composable
fun DecksScreen(
    onOpen: (String) -> Unit,
    onStudy: (String) -> Unit,
    onStats: () -> Unit = {},
    onBrowse: () -> Unit = {},
    onPractice: () -> Unit = {},
    onCatalog: () -> Unit = {},
) {
    val graph = LocalContext.current.graph
    // "now" is refreshed on resume so due counts stay current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val tree by remember(now) { graph.repository.observeDeckTree(now) }.collectAsState(null)
    val rows = remember(tree) { tree?.rows().orEmpty() }
    val reviewedToday by remember { graph.repository.observeReviewedToday() }.collectAsState(0)
    val scoreCount by remember { graph.repository.observeCharScoreCount() }.collectAsState(0)
    val weak by produceState(emptyList<CharQuality>(), scoreCount) { value = ui { graph.repository.charQuality().filter { it.weak } } }
    var creating by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickApkg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importStatus = "Импорт…"
            graph.appScope.launch {
                var running = true
                val result = runCatching {
                    AnkiImporter(context, graph.repository, graph.media).import(uri) { msg ->
                        // progress comes from the import thread; a late message must not hide the result
                        graph.appScope.launch(Dispatchers.Main) { if (running) importStatus = msg }
                    }
                }
                running = false
                importStatus = result.fold(
                    { r ->
                        "Импортировано: колод ${r.decks}, заметок ${r.notes}, новых карточек ${r.cards}" +
                            (if (r.reviews > 0) ", повторений из истории ${r.reviews}" else "") +
                            (if (r.media > 0) ", медиафайлов ${r.media}" else "") +
                            (if (r.cards == 0 && r.notes > 0) ". Эти карточки уже были импортированы раньше." else "") +
                            if (r.skipped > 0) " (пропущено ${r.skipped})" else ""
                    },
                    { e -> "Не удалось импортировать: ${e.message ?: e.javaClass.simpleName}" },
                )
                now = System.currentTimeMillis()
            }
        }
    }
    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importStatus = "Импорт…"
            graph.appScope.launch {
                importStatus = runCatching { TextImporter(context, graph.repository).import(uri) }.fold(
                    { r -> "Колода «${r.deck}»: импортировано карточек ${r.cards}" },
                    { e -> "Не удалось импортировать: ${e.message ?: e.javaClass.simpleName}" },
                )
                now = System.currentTimeMillis()
            }
        }
    }
    // download recognition models for the decks' languages in the background
    val languages = remember(tree) { tree?.byId?.values?.map { it.language }?.toSet().orEmpty() }
    LaunchedEffect(languages) { languages.forEach { graph.recognizers.get(it).prefetch() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Колоды", subtitle = "Сегодня повторено: $reviewedToday") {
                TextButton(onClick = onBrowse) { Text("Поиск") }
                TextButton(onClick = onStats) { Text("Статистика") }
                MenuButton(
                    "⋮",
                    listOf(
                        "Готовые колоды" to onCatalog,
                        "Импорт колоды Anki (.apkg)" to { pickApkg.launch(arrayOf("*/*")) },
                        "Импорт текста (CSV, TSV)" to { pickText.launch(arrayOf("text/*", "application/csv", "*/*")) },
                        "Тренировка почерка" to onPractice,
                    ),
                    Modifier.testTag("decks-menu"),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (weak.isNotEmpty()) item("practice") { PracticeBanner(weak, onPractice) }
                if (tree != null && rows.isEmpty()) item("empty") {
                    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Колод пока нет", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Добавьте готовую колоду — кана, кандзи по классам, частые японские слова, HSK, корейская лексика — " +
                                "или создайте свою, или импортируйте из Anki или текстового файла (меню «⋮»).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onCatalog, modifier = Modifier.testTag("open-catalog")) { Text("Готовые колоды") }
                    }
                }
                items(rows, key = { it.deck.id }) { r ->
                    DeckRowCard(
                        r,
                        onToggle = { graph.appScope.launch { graph.repository.setCollapsed(r.deck.id, !r.deck.collapsed) } },
                        onOpen = { onOpen(r.deck.id) },
                        onStudy = { onStudy(r.deck.id) },
                    )
                }
                item { Box(Modifier.padding(40.dp)) }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Колода")
        }
    }
    importStatus?.let { msg ->
        AlertDialog(
            onDismissRequest = { importStatus = null },
            title = { Text("Импорт") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importStatus = null }) { Text("OK") } },
        )
    }
    if (creating) {
        CreateDeckDialog(tree, onDismiss = { creating = false }) { name, lang, mode, parent ->
            creating = false
            graph.appScope.launch { onOpen(graph.repository.createDeck(name, lang, mode, parent).id) }
        }
    }
}

@Composable
private fun PracticeBanner(weak: List<CharQuality>, onPractice: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Потренировать почерк", style = MaterialTheme.typography.titleSmall)
                Text(
                    weak.take(8).joinToString(" ") { it.glyph } + if (weak.size > 8) " …" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            OutlinedButton(onClick = onPractice) { Text("Тренировать (${weak.size})") }
        }
    }
}

@Composable
private fun DeckRowCard(r: DeckRow, onToggle: () -> Unit, onOpen: () -> Unit, onStudy: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(start = (r.depth * 20).dp).clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.hasChildren) {
                TextButton(onClick = onToggle, modifier = Modifier.width(44.dp)) { Text(if (r.deck.collapsed) "▸" else "▾") }
            } else {
                Box(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(r.deck.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    "${languageName(r.deck.language)} · ${r.total} карт. · " +
                        if (r.deck.defaultAnswerMode == AnswerMode.HANDWRITING.name) "письмо" else "классика",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Counts(r.newCount, r.learnCount, r.reviewCount)
            Button(onClick = onStudy, enabled = r.newCount + r.dueCount > 0) { Text("Учить") }
        }
    }
}

/** Anki's three numbers: new (blue), learning (red), due reviews (green). */
@Composable
fun Counts(new: Int, learn: Int, review: Int) {
    Row(Modifier.padding(horizontal = 4.dp)) {
        Text("$new", color = NewColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text("$learn", color = LearnColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text("$review", color = DueColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDeckDialog(tree: DeckTree?, onDismiss: () -> Unit, onCreate: (String, String, AnswerMode, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("ja") }
    var mode by remember { mutableStateOf(AnswerMode.HANDWRITING) }
    var parent by remember { mutableStateOf<DeckEntity?>(null) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая колода") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                Text("Язык", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Languages.forEach { (code, n) -> FilterChip(lang == code, { lang = code }, { Text(n) }) }
                }
                Text("Ответ по умолчанию", style = MaterialTheme.typography.labelLarge)
                ModeChips(mode) { mode = it }
                if (tree != null && tree.byId.isNotEmpty()) {
                    OutlinedButton(onClick = { picking = true }) { Text("Внутри колоды: " + (parent?.name ?: "нет")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.ifBlank { "Колода" }, lang, mode, parent?.id) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (picking && tree != null) {
        DeckPickerDialog(
            tree, "Родительская колода", parent?.id,
            onPick = { parent = it; picking = false }, onDismiss = { picking = false }, allowNone = "Нет (верхний уровень)",
        )
    }
}

@Composable
fun ModeChips(mode: AnswerMode, onChange: (AnswerMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(mode == AnswerMode.CLASSIC, { onChange(AnswerMode.CLASSIC) }, { Text("Классика") })
        FilterChip(mode == AnswerMode.HANDWRITING, { onChange(AnswerMode.HANDWRITING) }, { Text("Письмо") })
    }
}
