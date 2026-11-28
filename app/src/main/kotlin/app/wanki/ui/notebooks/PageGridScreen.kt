package app.wanki.ui.notebooks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.wanki.R
import app.wanki.data.PageEntity
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.ItemMenu
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.PageThumbnail
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.rememberUiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * All pages of a notebook as thumbnails. Tap opens a page ([onOpen] gets its id); long press on a thumbnail
 * offers insert before/after, duplicate and delete; the handle under a thumbnail drags it to a new position.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageGridScreen(notebookId: String, onBack: () -> Unit, onOpen: (pageId: String) -> Unit) {
    val repository = LocalContext.current.graph.repository
    val undo = LocalUndo.current
    val scope = rememberUiScope()
    val notebookRow by produceState<app.wanki.data.NotebookEntity?>(null, notebookId) { value = repository.notebook(notebookId) }
    val notebook = notebookRow?.title
    val pageSize = notebookRow?.let { runCatching { app.wanki.data.PageSize.valueOf(it.pageSize) }.getOrNull() } ?: app.wanki.data.PageSize.A4
    val pages by remember(notebookId) { repository.observePages(notebookId) }.collectAsState(null)

    // the order on screen while dragging; the database order replaces it once the move is stored
    val shown = remember(pages) { mutableStateListOf<PageEntity>().apply { addAll(pages.orEmpty()) } }
    var drag by remember { mutableStateOf<PageDrag?>(null) }
    var scrollToEnd by remember { mutableStateOf(false) }

    fun attempt(failure: String, work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                undo.say(failure)
            }
        }
    }

    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        shown.add(to.index, shown.removeAt(from.index))
    }
    LaunchedEffect(shown.size, scrollToEnd) {
        if (scrollToEnd && shown.isNotEmpty()) { gridState.animateScrollToItem(shown.lastIndex); scrollToEnd = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Страницы", onBack = onBack, subtitle = notebook,
            primary = listOf(
                HeaderAction(R.drawable.ic_add, "Добавить страницу", "add-page") {
                    attempt("Не удалось добавить страницу") { repository.insertPage(notebookId, Int.MAX_VALUE); scrollToEnd = true }
                },
            ),
        )
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp),
            Modifier.fillMaxSize().testTag("page-grid"),
            state = gridState,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(shown, key = { _, p -> p.id }) { index, page ->
                ReorderableItem(reorder, key = page.id) { dragging ->
                    var menu by remember { mutableStateOf(false) }
                    val only = shown.size <= 1
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth()) {
                            PageThumbnail(
                                page,
                                Modifier.fillMaxWidth()
                                    .border(if (dragging) 3.dp else 1.dp, if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                    .testTag("page-thumb-$index")
                                    .combinedClickable(onClick = { onOpen(page.id) }, onLongClick = { menu = true }),
                                pageSize,
                            )
                            ItemMenu(
                                expanded = menu, onDismiss = { menu = false },
                                items = listOf(
                                    HeaderAction(R.drawable.ic_add, "Вставить перед", "insert-before") {
                                        attempt("Не удалось вставить страницу") { repository.insertPage(notebookId, index) }
                                    },
                                    HeaderAction(R.drawable.ic_add, "Вставить после", "insert-after") {
                                        attempt("Не удалось вставить страницу") { repository.insertPage(notebookId, index + 1) }
                                    },
                                    HeaderAction(R.drawable.ic_content_copy, "Дублировать", "duplicate") {
                                        attempt("Не удалось дублировать страницу") { repository.duplicatePage(page.id) }
                                    },
                                    // the last page of a notebook stays
                                    HeaderAction(R.drawable.ic_delete, "Удалить", "delete", enabled = !only) {
                                        undo.deleted(TrashKind.PAGE, page.id)
                                    },
                                ),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Box(
                                Modifier.size(48.dp).testTag("page-drag-$index")
                                    .onPointersLifted { drag?.lift() }
                                    .draggableHandle(
                                        onDragStarted = { drag = PageDrag(index) },
                                        onDragStopped = {
                                            val end = drag?.end(shown.indexOfFirst { it.id == page.id })
                                            drag = null
                                            when (end) {
                                                // a cancelled gesture (ACTION_CANCEL) stores nothing: back to the stored order
                                                PageDragEnd.Restore -> { shown.clear(); shown.addAll(pages.orEmpty()) }
                                                is PageDragEnd.Move -> attempt("Не удалось переместить страницу") {
                                                    try {
                                                        repository.movePage(notebookId, end.from, end.to)
                                                    } catch (e: Exception) {
                                                        // show the stored order again
                                                        shown.clear(); shown.addAll(repository.pages(notebookId))
                                                        throw e
                                                    }
                                                }
                                                PageDragEnd.Stay, null -> {}
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = "Перетащить страницу ${index + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
}
