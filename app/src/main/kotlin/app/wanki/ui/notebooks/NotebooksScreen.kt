package app.wanki.ui.notebooks

import app.wanki.ui.common.ui
import app.wanki.ui.common.ErrorSnackbar
import app.wanki.ui.common.rememberUiScope
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.wanki.R
import androidx.compose.ui.text.style.TextOverflow
import app.wanki.data.ListOrder
import app.wanki.data.NotebookEntity
import app.wanki.data.Settings.Companion.SECTION_NOTEBOOKS
import app.wanki.data.TrashKind
import app.wanki.ui.common.FirstPageThumbnail
import app.wanki.ui.common.ListActions
import app.wanki.ui.common.ListCard
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.RenameDialog
import app.wanki.ui.common.itemMenu
import app.wanki.ui.common.sortMenu
import java.text.DateFormat
import java.util.Date
import app.wanki.graph
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.ListState
import app.wanki.ui.common.NotebookLanguages
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.label
import app.wanki.ui.common.languageName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NotebooksScreen(
    onOpen: (notebookId: String, pageId: String?) -> Unit,
    onOpenHit: (PageSearchHit) -> Unit,
) {
    val graph = LocalContext.current.graph
    val notebooks by remember { graph.repository.observeNotebooks(NotebookKind.NOTEBOOK) }.collectAsState(null)
    val sort = graph.settings.listSort(SECTION_NOTEBOOKS)
    val sorted = remember(notebooks, sort) {
        ListOrder.sort(notebooks.orEmpty(), sort, { it.notebook.pinned }, { it.notebook.title }, { it.notebook.updatedAt }, { it.notebook.createdAt })
    }
    val undo = LocalUndo.current
    val actions = remember { ListActions(graph.repository, undo, graph.appScope) }
    var renaming by remember { mutableStateOf<NotebookEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settingsFor by remember { mutableStateOf<NotebookEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PageSearchHit>>(emptyList()) }
    LaunchedEffect(query) {
        delay(250)
        hits = ui { graph.repository.searchPages(query, NotebookKind.NOTEBOOK) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Тетради", subtitle = "Рукописный текст распознаётся автоматически, по нему работает поиск",
                menu = sortMenu(graph.settings, SECTION_NOTEBOOKS),
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск по написанному от руки") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (query.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hits.isEmpty()) item { Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(hits, key = { it.pageId }) { h ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenHit(h) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${h.notebookTitle} · стр. ${h.pageIndex + 1}", style = MaterialTheme.typography.labelLarge)
                                Text(snippet(h.recognizedText, query), maxLines = 3)
                            }
                        }
                    }
                }
            } else ListState(
                loading = notebooks == null, isEmpty = notebooks.orEmpty().isEmpty(),
                emptyTitle = "Пока нет тетрадей", emptyHint = "Пишите от руки: конспекты, прописи, черновики",
                emptyAction = HeaderAction(R.drawable.ic_book_2, "Новая тетрадь", "notebooks-empty-new") { creating = true },
            ) {
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sorted, key = { it.notebook.id }) { n ->
                        val nb = n.notebook
                        ListCard(
                            "notebook-${nb.id}", nb.pinned,
                            itemMenu(
                                nb.pinned,
                                onRename = { renaming = nb },
                                onDuplicate = { actions.duplicateNotebook(nb.id) },
                                onPin = { actions.pin(TrashKind.NOTEBOOK, nb.id, !nb.pinned) },
                                onDelete = { undo.deleted(TrashKind.NOTEBOOK, nb.id) },
                                onSettings = { settingsFor = nb },
                            ),
                            onClick = { onOpen(nb.id, null) },
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FirstPageThumbnail(nb.id)
                                Text(nb.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${n.pageCount} стр. · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!notebooks.isNullOrEmpty()) ExtendedFloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("+ Тетрадь") }
    }

    renaming?.let { nb ->
        RenameDialog(nb.title, onDismiss = { renaming = null }) { actions.renameNotebook(nb.id, it); renaming = null }
    }

    if (creating) {
        NotebookSettingsDialog(null, onDismiss = { creating = false }) { title, lang, template, size, landscape ->
            creating = false
            graph.appScope.launch {
                val n = graph.repository.createNotebook(title, template, lang, size, landscape)
                onOpen(n.id, null)
            }
        }
    }
    settingsFor?.let { nb ->
        NotebookSettingsDialog(nb, onDismiss = { settingsFor = null }) { title, lang, template, size, landscape ->
            settingsFor = null
            graph.appScope.launch { graph.repository.setNotebookSettings(nb.id, title, lang, template, size, landscape) }
        }
    }
}

private fun snippet(text: String, query: String): String {
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return text.take(120)
    val start = (i - 40).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + text.substring(start, (i + query.length + 60).coerceAtMost(text.length))
}

/** A notebook: one page at a time with page navigation. */
@Composable
fun NotebookScreen(notebookId: String, initialPageId: String?, onBack: () -> Unit, onGrid: (currentPageId: String) -> Unit) {
    val graph = LocalContext.current.graph
    var settingsRev by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val notebook by produceState<NotebookEntity?>(null, notebookId, settingsRev) { value = ui { graph.repository.notebook(notebookId) } }
    val pages by remember(notebookId) { graph.repository.observePages(notebookId) }.collectAsState(emptyList<PageEntity>())
    var index by rememberSaveable(notebookId) { mutableStateOf<Int?>(null) }
    // a page that was just added: the current page stays on screen until it arrives through the flow
    val scope = rememberUiScope()
    val adder = remember(notebookId) { PageAdder(scope) }
    val addError by adder.error.collectAsState()

    LaunchedEffect(pages, initialPageId) {
        if (index == null && pages.isNotEmpty()) {
            index = initialPageId?.let { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        }
    }
    LaunchedEffect(pages, adder.pendingIndex) {
        adder.arrived(pages.size)?.let { index = it }
    }
    val nb = notebook ?: return
    val i = index ?: return
    val page = pages.getOrNull(i) ?: return
    val pageUi = rememberPageUi()
    val state = remember(page.id) { PageEditorState(graph, nb, page, infinite = false, ui = pageUi) }
    androidx.compose.runtime.SideEffect { state.notebook = nb }
    fun addPage() = adder.add(pages.size) { graph.repository.addPage(nb) }

    Box(Modifier.fillMaxSize()) {
        PageEditor(
            state, nb.title, onBack, Modifier.fillMaxSize(),
            subtitle = "стр. ${i + 1} из ${pages.size}",
            primary = listOf(
                HeaderAction(R.drawable.ic_grid_view, "Все страницы", "pages-grid") { onGrid(page.id) },
                HeaderAction(R.drawable.ic_add, "Добавить страницу", "add-page", enabled = adder.pendingIndex == null) { addPage() },
                state.textAction(),
            ),
            menu = listOf(
                HeaderAction(R.drawable.ic_settings, "Настройки тетради", "menu-notebook-settings") { showSettings = true },
                state.paperAction(), state.fitAction(),
            ),
            pageBar = { PageNavBar(i, pages.size, { index = it.coerceIn(0, pages.lastIndex) }) },
        )
        if (showSettings) NotebookSettingsDialog(nb, onDismiss = { showSettings = false }) { title, lang, template, size, landscape ->
            showSettings = false
            graph.appScope.launch {
                graph.repository.setNotebookSettings(nb.id, title, lang, template, size, landscape)
                ui { settingsRev++ }
            }
        }
        ErrorSnackbar(addError, "Не удалось добавить страницу", ::addPage, Modifier.align(Alignment.BottomCenter), adder::dismissError)
    }
}
