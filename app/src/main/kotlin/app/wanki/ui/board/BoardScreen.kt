package app.wanki.ui.board

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
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.notebooks.PageEditor
import app.wanki.ui.notebooks.PageEditorState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** All boards: create as many as needed, each one an infinite canvas. */
@Composable
fun BoardsScreen(onOpen: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val boards by remember { graph.repository.observeNotebooks(NotebookKind.BOARD) }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Доски", subtitle = "Бесконечный холст: рисуйте и пишите где угодно")
            if (boards.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Пока нет досок", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(180.dp), Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(boards, key = { it.notebook.id }) { b ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(b.notebook.id) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(b.notebook.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(b.notebook.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("+ Доска")
        }
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
    val board by produceState<Pair<NotebookEntity, PageEntity>?>(null, boardId) { value = graph.repository.boardPage(boardId) }
    val (notebook, page) = board ?: return
    val state = remember(page.id) { PageEditorState(graph, notebook, page, infinite = true) }
    var confirmDelete by remember { mutableStateOf(false) }
    PageEditor(state, Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(notebook.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить доску «${notebook.title}»?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    graph.appScope.launch { graph.repository.deleteNotebook(notebook.id) }
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
