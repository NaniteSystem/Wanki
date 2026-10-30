package app.wanki.ui.notebooks

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.AppGraph
import app.wanki.data.NotebookEntity
import app.wanki.data.PageEntity
import app.wanki.ink.InkAffine
import app.wanki.input.InkClip
import app.wanki.ui.common.Undo
import app.wanki.ui.common.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Перенести на страницу»: every page of the notebook but the current one («стр. N», tagged `move-page-<index>`), and
 * «Новая страница в конце» (`move-page-new`). [onPick] gets the chosen page, or null for a new one.
 */
@Composable
fun MoveToPageDialog(pages: List<PageEntity>, currentPageId: String, onPick: (PageEntity?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перенести на страницу") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(pages.filter { it.id != currentPageId }, key = { it.id }) { p ->
                    TextButton(
                        onClick = { onPick(p) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("move-page-${p.pageIndex}"),
                    ) { Text("стр. ${p.pageIndex + 1}", modifier = Modifier.fillMaxWidth()) }
                }
                item {
                    TextButton(
                        onClick = { onPick(null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("move-page-new"),
                    ) { Text("Новая страница в конце", modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Moves the lasso selection of [state] to [target] (null: a new page at the end of [notebook]), Decision 9:
 * - the repository writes the target, then the source without the strokes, in one transaction; only then are the
 *   strokes taken off the canvas (one undo step there: canvas undo brings them back as a copy);
 * - the shell snackbar «Перенесено на стр. N» · «Отменить» puts them back on the source first (the open editor's undo
 *   when the take is still its last step, else a paste or a write to the stored page) and then takes them off the target.
 * Failures are shown in the snackbar; the strokes are always on at least one page.
 */
fun moveSelectionToPage(graph: AppGraph, undo: Undo, state: PageEditorState, notebook: NotebookEntity, target: PageEntity?) {
    val layer = state.layer
    val strokes = layer.inLayerOrder(layer.selection.toList())
    val bounds = layer.selectionBounds()
    if (strokes.isEmpty() || bounds == null || !state.editable) return
    val from = state.page
    val encodeRest = layer.snapshotEncoderWithout(strokes)
    graph.appScope.launch {
        try {
            val to = target ?: graph.repository.addPage(notebook)
            val rest = withContext(Dispatchers.Default) { encodeRest() }
            val keys = graph.repository.moveToPage(from, rest, to, strokes)
            ui {
                layer.selection.clear()
                layer.selection.addAll(layer.inLayerOrder(strokes))
                val clip = layer.takeSelection()
                val take = layer.undoTop
                undo.moved("Перенесено на стр. ${to.pageIndex + 1}") {
                    moveBack(graph, state, take, clip, from, bounds.left, bounds.top, to, keys)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${from.id}: strokes not moved", e)
            undo.say("Не удалось перенести")
        }
    }
}

/** «Отменить» of a move: the strokes go back to [from] (saved), then leave [to]. */
private suspend fun moveBack(
    graph: AppGraph, mover: PageEditorState, take: Any?, clip: InkClip, from: PageEntity, left: Float, top: Float,
    to: PageEntity, keys: List<Long>,
) = ui {
    val source = PageEditorState.editorOf(from.id)
    if (source != null) {
        val b = clip.bounds()
        if (source === mover && source.layer.undoTop === take) source.layer.undo()
        else if (b != null) source.layer.paste(clip, left + (b.left + b.right) / 2, top + (b.top + b.bottom) / 2)
        source.saver.request()
        source.saver.flush()
        // not stored on the source: the strokes stay on the target too rather than only in memory
        source.saver.error.value?.let { throw IllegalStateException("source page not saved", it) }
    } else {
        graph.repository.appendInk(from, clip.strokes.map(InkAffine.translate(left, top)::apply))
    }
    val target = PageEditorState.editorOf(to.id)
    if (target != null) target.layer.removeByKeys(keys) else graph.repository.takeBackFromPage(to.id, keys)
}
