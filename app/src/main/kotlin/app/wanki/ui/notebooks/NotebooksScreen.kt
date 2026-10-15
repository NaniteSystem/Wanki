package app.wanki.ui.notebooks

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wanki.data.NotebookKind
import app.wanki.data.PageEntity
import app.wanki.data.PageSearchHit
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ui.common.Languages
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.label
import app.wanki.ui.common.languageName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NotebooksScreen(onOpen: (notebookId: String, pageId: String?) -> Unit) {
    val graph = LocalContext.current.graph
    val notebooks by remember { graph.repository.observeNotebooks(NotebookKind.NOTEBOOK) }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PageSearchHit>>(emptyList()) }
    LaunchedEffect(query) {
        delay(250)
        hits = graph.repository.searchPages(query)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Тетради", subtitle = "Рукописный текст распознаётся автоматически, по нему работает поиск")
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск по написанному от руки") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (query.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hits.isEmpty()) item { Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(hits, key = { it.pageId }) { h ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(h.notebookId, h.pageId) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${h.notebookTitle} · стр. ${h.pageIndex + 1}", style = MaterialTheme.typography.labelLarge)
                                Text(snippet(h.recognizedText, query), maxLines = 3)
                            }
                        }
                    }
                }
            } else if (notebooks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Пока нет тетрадей. Создайте первую.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(180.dp),
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notebooks, key = { it.notebook.id }) { n ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(n.notebook.id, null) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(n.notebook.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${n.pageCount} стр. · ${runCatching { PageTemplate.valueOf(n.notebook.template).label() }.getOrDefault("")} · ${languageName(n.notebook.language)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("+ Тетрадь") }
    }

    if (creating) {
        CreateNotebookDialog(onDismiss = { creating = false }) { title, template, lang ->
            creating = false
            graph.appScope.launch {
                val n = graph.repository.createNotebook(title, template, lang)
                onOpen(n.id, null)
            }
        }
    }
}

private fun snippet(text: String, query: String): String {
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return text.take(120)
    val start = (i - 40).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + text.substring(start, (i + query.length + 60).coerceAtMost(text.length))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateNotebookDialog(onDismiss: () -> Unit, onCreate: (String, PageTemplate, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(PageTemplate.LINED) }
    var lang by remember { mutableStateOf("ja") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая тетрадь") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                Text("Шаблон страниц", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageTemplate.entries.forEach { t -> FilterChip(template == t, { template = t }, { Text(t.label()) }) }
                }
                Text("Язык записей (для распознавания)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Languages.forEach { (code, name) -> FilterChip(lang == code, { lang = code }, { Text(name) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title.ifBlank { "Тетрадь" }, template, lang) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** A notebook: one page at a time with page navigation. */
@Composable
fun NotebookScreen(notebookId: String, initialPageId: String?, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val notebook by produceState<app.wanki.data.NotebookEntity?>(null, notebookId) { value = graph.repository.notebook(notebookId) }
    val pages by remember(notebookId) { graph.repository.observePages(notebookId) }.collectAsState(emptyList<PageEntity>())
    var index by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pages, initialPageId) {
        if (index == null && pages.isNotEmpty()) {
            index = initialPageId?.let { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        }
    }
    val nb = notebook ?: return
    val i = index ?: return
    val page = pages.getOrNull(i) ?: return
    val state = remember(page.id) { PageEditorState(graph, nb, page, infinite = false) }

    PageEditor(state, Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(nb.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { index = (i - 1).coerceAtLeast(0) }, enabled = i > 0) { Text("◀") }
            Text("${i + 1}/${pages.size}", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { index = (i + 1).coerceAtMost(pages.lastIndex) }, enabled = i < pages.lastIndex) { Text("▶") }
            TextButton(onClick = {
                scope.launch {
                    graph.repository.addPage(nb, state.template)
                    index = pages.size // the new page arrives through the flow
                }
            }) { Text("+ стр.") }
        }
    }
}
