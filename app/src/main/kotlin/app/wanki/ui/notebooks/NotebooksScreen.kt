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
import app.wanki.ui.common.listAction
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
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import app.wanki.importer.ImportResult
import app.wanki.importer.PageImporter
import app.wanki.importer.message
import kotlinx.coroutines.launch

@Composable
fun NotebooksScreen(
    onOpen: (notebookId: String, pageId: String?) -> Unit,
    onOpenHit: (PageSearchHit, query: String) -> Unit,
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
                        Card(Modifier.fillMaxWidth().clickable { onOpenHit(h, query) }) {
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
            graph.appScope.listAction(undo, "Не удалось создать тетрадь") {
                val n = graph.repository.createNotebook(title, template, lang, size, landscape)
                onOpen(n.id, null)
            }
        }
    }
    settingsFor?.let { nb ->
        NotebookSettingsDialog(nb, onDismiss = { settingsFor = null }) { title, lang, template, size, landscape ->
            settingsFor = null
            graph.appScope.listAction(undo, "Не удалось сохранить настройки") {
                graph.repository.setNotebookSettings(nb.id, title, lang, template, size, landscape)
            }
        }
    }
}

/**
 * A launcher of the system's multiple-documents picker for the given MIME types, or null where results cannot be
 * received. Tests answer it through [PicturePickers.registry].
 */
@Composable
private fun rememberDocumentsPicker(onPicked: (List<Uri>) -> Unit): ((Array<String>) -> Unit)? {
    val owner = PicturePickers.registry?.let { r ->
        remember(r) { object : ActivityResultRegistryOwner { override val activityResultRegistry = r } }
    } ?: LocalActivityResultRegistryOwner.current ?: return null
    var launch: ((Array<String>) -> Unit)? = null
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onPicked(uris)
        }
        launch = { types -> launcher.launch(types) }
    }
    return launch
}

private fun snippet(text: String, query: String): String {
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return text.take(120)
    val start = (i - 40).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + text.substring(start, (i + query.length + 60).coerceAtMost(text.length))
}

/** A notebook: one page at a time with page navigation. */
@Composable
fun NotebookScreen(
    notebookId: String, initialPageId: String?, onBack: () -> Unit, onGrid: (currentPageId: String) -> Unit,
    /** Search query whose lines are marked on the opening page. */
    highlight: String? = null,
    /** The highlight has been shown: the caller drops it from the route. */
    onHighlightShown: () -> Unit = {},
) {
    val graph = LocalContext.current.graph
    app.wanki.input.OpenTrace.mark("NotebookScreen composing")
    val undo = LocalUndo.current
    var settingsRev by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    // the opening page's ink is read now, with the notebook and its pages: the page composes from those, without
    // waiting for the queries and the flow below
    val scope = rememberUiScope()
    val preload = remember(notebookId) {
        scope.preloadInk(graph, notebookId) { pages -> initialPageId?.let { id -> pages.firstOrNull { it.id == id } } ?: pages.firstOrNull() }
    }
    val opening by produceState<Opening?>(null, preload) { value = preload.opening.await() }
    val stored by produceState<NotebookEntity?>(null, notebookId, settingsRev) { value = ui { graph.repository.notebook(notebookId) } }
    val notebook = stored ?: opening?.notebook
    val flowPages by remember(notebookId) { graph.repository.observePages(notebookId) }.collectAsState<List<PageEntity>, List<PageEntity>?>(null)
    val pages = flowPages ?: opening?.pages ?: emptyList()
    var index by rememberSaveable(notebookId) { mutableStateOf<Int?>(null) }
    // a page that was just added: the current page stays on screen until it arrives through the flow
    val adder = remember(notebookId) { PageAdder(scope) }
    val addError by adder.error.collectAsState()

    if (notebook != null) app.wanki.input.OpenTrace.mark("notebook arrived")
    if (pages.isNotEmpty()) app.wanki.input.OpenTrace.mark("pages arrived")
    // the opening page is known as soon as the pages are: no frame waits for the index to be stored
    val initialIndex = if (pages.isEmpty()) null else initialPageId?.let { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
    androidx.compose.runtime.SideEffect { if (index == null && initialIndex != null) index = initialIndex }
    LaunchedEffect(pages, adder.pendingIndex) {
        adder.arrived(pages.size)?.let { index = it }
    }
    val nb = notebook ?: return
    val i = index ?: initialIndex ?: return
    val page = pages.getOrNull(i) ?: return
    val pageUi = rememberPageUi()
    // the marks belong to the page the search hit opened; once the reader turns away they are not shown again
    val mark = highlight.takeIf { page.id == initialPageId }
    val state = remember(page.id) {
        app.wanki.input.OpenTrace.mark("editor state created")
        PageEditorState(graph, nb, page, infinite = false, ui = pageUi, preload = preload, highlightQuery = mark, onHighlightShown = onHighlightShown)
            .also { it.startLoad(scope) }
    }
    androidx.compose.runtime.SideEffect { state.notebook = nb }
    fun addPage() = adder.add(pages.size) { graph.repository.addPage(nb) }
    var moving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // the first imported page, shown once it arrives through the flow
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pages, jumpTo) {
        jumpTo?.let { if (it < pages.size) { index = it; jumpTo = null } }
    }
    val appContext = LocalContext.current.applicationContext
    val pick = rememberDocumentsPicker { uris ->
        importing = true
        val after = i
        graph.appScope.launch {
            // whatever happens, the progress dialog ends with a message
            val r = try {
                PageImporter(appContext, graph.repository, graph.media).import(uris, nb.id, after)
            } catch (e: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { ui { importing = false } }
                throw e
            } catch (e: Throwable) {
                Log.e("Wanki", "notebook ${nb.id}: import failed", e)
                ImportResult.Failed("Не удалось добавить страницы")
            }
            ui {
                importing = false
                if (r is ImportResult.Added && r.pages > 0) jumpTo = after + 1
                undo.say(r.message())
            }
        }
    }

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
                HeaderAction(R.drawable.ic_picture_as_pdf, "Экспорт в PDF", "menu-export-pdf") { exporting = true },
            ) + listOfNotNull(pick?.let { p ->
                HeaderAction(R.drawable.ic_upload_file, "Импорт PDF или картинок", "menu-import") {
                    try {
                        p(arrayOf("application/pdf", "image/*"))
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { undo.say("Нет приложения для выбора файлов") }
                    }
                }
            }),
            pageBar = { PageNavBar(i, pages.size, { index = it.coerceIn(0, pages.lastIndex) }) },
            // a fling past the last (or before the first) page does nothing: pages are added with «+»
            onPageSwipe = { dir -> index = (i + dir).coerceIn(0, pages.lastIndex) },
            onMoveSelection = { moving = true },
        )
        if (exporting) ExportDialog(
            nb, page,
            allPages = { graph.repository.pages(nb.id) },
            beforeExport = { state.saver.request(); state.saver.flush() },
            onDismiss = { exporting = false },
        )
        if (importing) ProgressDialog("Добавляю страницы…", "import-progress")
        if (moving) MoveToPageDialog(pages, page.id, onPick = { target ->
            moving = false
            moveSelectionToPage(graph, undo, state, nb, target)
        }, onDismiss = { moving = false })
        if (showSettings) NotebookSettingsDialog(nb, onDismiss = { showSettings = false }) { title, lang, template, size, landscape ->
            showSettings = false
            graph.appScope.listAction(undo, "Не удалось сохранить настройки") {
                graph.repository.setNotebookSettings(nb.id, title, lang, template, size, landscape)
                ui { settingsRev++ }
            }
        }
        ErrorSnackbar(addError, "Не удалось добавить страницу", ::addPage, Modifier.align(Alignment.BottomCenter), adder::dismissError)
    }
}
