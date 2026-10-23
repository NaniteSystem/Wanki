package app.wanki.ui.board

import app.wanki.ui.common.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.unit.dp
import app.wanki.data.NotebookEntity
import app.wanki.data.NotebookKind
import app.wanki.data.PageEntity
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import app.wanki.R
import androidx.compose.ui.text.style.TextOverflow
import app.wanki.data.ListOrder
import app.wanki.data.Settings.Companion.SECTION_BOARDS
import app.wanki.ui.common.FirstPageThumbnail
import app.wanki.ui.common.ListActions
import app.wanki.ui.common.ListCard
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.RenameDialog
import app.wanki.ui.common.itemMenu
import app.wanki.ui.common.sortMenu
import kotlinx.coroutines.flow.map
import app.wanki.ui.common.HeaderAction
import app.wanki.ui.common.ListState
import app.wanki.ui.notebooks.PageEditor
import app.wanki.ui.notebooks.rememberPageUi
import app.wanki.ui.notebooks.fitAction
import app.wanki.ui.notebooks.textAction
import app.wanki.ui.notebooks.PageEditorState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** All boards: create as many as needed, each one an infinite canvas. */
@Composable
fun BoardsScreen(onOpen: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val boards by remember { graph.repository.observeNotebooks(NotebookKind.BOARD) }.collectAsState(null)
    val sort = graph.settings.listSort(SECTION_BOARDS)
    val sorted = remember(boards, sort) {
        ListOrder.sort(boards.orEmpty(), sort, { it.notebook.pinned }, { it.notebook.title }, { it.notebook.updatedAt }, { it.notebook.createdAt })
    }
    val undo = LocalUndo.current
    val actions = remember { ListActions(graph.repository, undo, graph.appScope) }
    var renaming by remember { mutableStateOf<NotebookEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Доски", subtitle = "Бесконечный холст: рисуйте и пишите где угодно", menu = sortMenu(graph.settings, SECTION_BOARDS))
            ListState(
                loading = boards == null, isEmpty = boards.orEmpty().isEmpty(),
                emptyTitle = "Пока нет досок", emptyHint = "Большой холст для схем и рисунков",
                emptyAction = HeaderAction(R.drawable.ic_dashboard, "Новая доска", "boards-empty-new") { creating = true },
            ) {
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sorted, key = { it.notebook.id }) { b ->
                        val nb = b.notebook
                        ListCard(
                            "board-${nb.id}", nb.pinned,
                            itemMenu(
                                nb.pinned,
                                onRename = { renaming = nb },
                                onDuplicate = { actions.duplicateNotebook(nb.id) },
                                onPin = { actions.pin(TrashKind.BOARD, nb.id, !nb.pinned) },
                                onDelete = { undo.deleted(TrashKind.BOARD, nb.id) },
                            ),
                            onClick = { onOpen(nb.id) },
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FirstPageThumbnail(nb.id, board = true)
                                Text(nb.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!boards.isNullOrEmpty()) ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Доска")
        }
    }
    renaming?.let { nb ->
        RenameDialog(nb.title, onDismiss = { renaming = null }) { actions.renameNotebook(nb.id, it); renaming = null }
    }
    if (creating) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Новая доска") },
            text = { OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    graph.appScope.launch { onOpen(graph.repository.createBoard(title).id) }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } },
        )
    }
}

/** One board: pinch to zoom, two fingers (or one, with a pen nearby) to move. */
@Composable
fun BoardScreen(boardId: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val board by produceState<Pair<NotebookEntity, PageEntity>?>(null, boardId) { value = ui { graph.repository.boardPage(boardId) } }
    val (notebook, page) = board ?: return
    val pageUi = rememberPageUi()
    val state = remember(page.id) { PageEditorState(graph, notebook, page, infinite = true, ui = pageUi) }
    var confirmDelete by remember { mutableStateOf(false) }
    PageEditor(
        state, notebook.title, onBack, Modifier.fillMaxSize(),
        menu = listOf(
            state.fitAction(), state.textAction(),
            HeaderAction(R.drawable.ic_delete, "Удалить", "menu-delete") { confirmDelete = true },
        ),
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить доску «${notebook.title}»?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    graph.appScope.launch { graph.repository.delete(TrashKind.BOARD, notebook.id) }
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
