package app.wanki.ui.board

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.wanki.graph
import app.wanki.ui.notebooks.PageEditor
import app.wanki.ui.notebooks.PageEditorState

/** One infinite white board: draw anywhere, pinch to zoom, two fingers (or one, with a pen) to move. */
@Composable
fun BoardScreen() {
    val graph = LocalContext.current.graph
    val board by produceState<Pair<app.wanki.data.NotebookEntity, app.wanki.data.PageEntity>?>(null) {
        value = graph.repository.board()
    }
    val (notebook, page) = board ?: return
    val state = remember(page.id) { PageEditorState(graph, notebook, page, infinite = true) }
    PageEditor(state, Modifier.fillMaxSize()) {
        Text("Доска", style = MaterialTheme.typography.titleMedium)
    }
}
