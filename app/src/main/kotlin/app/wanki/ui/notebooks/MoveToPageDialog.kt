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
import app.wanki.data.newId
import app.wanki.ink.InkAffine
import app.wanki.input.InkClip
import app.wanki.input.toEntity
import app.wanki.data.MovedInk
import app.wanki.ui.common.Undo
import app.wanki.ui.common.ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * - the strokes leave the canvas at once (one undo step there: canvas undo brings them back as a copy), but the
 *   editor's saves are held until the repository has written the target, then the source without them, in one
 *   transaction; then the source is saved from the canvas again. So whatever fails or ends (the editor closing
 *   meanwhile, process death), the strokes are stored on exactly one page;
 * - a failed move puts them back on the canvas and shows the error;
 * - the shell snackbar «Перенесено на стр. N» · «Отменить» puts them back on the source first (the open editor's undo
 *   when the take is still its last step, else a paste or a write to the stored page) and then takes them off the target.
 */
fun moveSelectionToPage(graph: AppGraph, undo: Undo, state: PageEditorState, notebook: NotebookEntity, target: PageEntity?) {
    val layer = state.layer
    val strokes = layer.inLayerOrder(layer.selection.toList())
    val objects = layer.selectedObjectList().map { it.toEntity(state.page.id) }
    val bounds = layer.selectionBounds()
    if ((strokes.isEmpty() && objects.isEmpty()) || bounds == null || !state.editable) return
    val from = state.page
    state.saveHolds++
    val clip = layer.takeSelection()
    val take = layer.undoTop
    val encodeRest = layer.snapshotEncoder()
    val restObjects = state.objectEntities()
    // both pages count as being written from here, not from when the coroutine starts: an editor of either opening
    // before the write began (the page opened again right after the move) read it from before the move, and its next
    // save stored the moved strokes on the source again (doubled) or dropped them from the target. A new page gets its
    // id now, so an editor of it cannot read it empty between its insert and the move either.
    val toId = target?.id ?: newId()
    PageWrites.launchWrite(graph.appScope, from.id, toId) {
        var to: PageEntity? = null
        var keys: MovedInk? = null
        try {
            val page = target ?: graph.repository.addPage(notebook, id = toId)
            val rest = withContext(Dispatchers.Default) { encodeRest() }
            keys = graph.repository.moveToPage(from, rest, page, strokes, objects, restObjects)
            to = page
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Wanki", "page ${from.id}: strokes not moved", e)
        }
        ui {
            try {
                // not moved: back on the canvas (saved below, also when the editor has closed meanwhile)
                if (keys == null) layer.restoreTake(take, clip, bounds.left, bounds.top)
            } finally {
                state.saveHolds--
            }
            // a closed editor's layer is saved too, unless the page was opened again and has loaded meanwhile (its new
            // editor holds the page now); one still loading reads the page again after this save
            if (state.editable || PageEditorState.openEditorOf(from.id)?.loaded != true) state.saver.request()
            val k = keys
            val page = to
            if (k == null || page == null) undo.say("Не удалось перенести")
            else undo.moved("Перенесено на стр. ${page.pageIndex + 1}") {
                moveBack(graph, state, take, clip, from, bounds.left, bounds.top, page, k)
            }
        }
    }
}

/**
 * Page [pageId]'s editor once it has loaded (waiting while one loads), or null once the stored page may be written:
 * no editor loads it and the last save of one that closed is done ([PageWrites.editorOrIdle]). The answer holds until
 * the caller next suspends. Main thread.
 */
private suspend fun editorOrStored(pageId: String): PageEditorState? = PageWrites.editorOrIdle(
    pageId,
    loading = { PageEditorState.openEditorOf(pageId)?.loaded == false },
    editor = { PageEditorState.awaitEditor(pageId) },
)

/**
 * «Отменить» of a move: the strokes go back to [from] (saved), then leave [to]. Each page is changed through its open
 * editor when it has one, waiting for one that is still loading (writing the stored page under it would be lost, or
 * doubled, by its next save); only a page without an editor is changed in the database, after the last save of an
 * editor that closed. Nothing suspends between finding the editor (or none) and changing the page.
 */
private suspend fun moveBack(
    graph: AppGraph, mover: PageEditorState, take: Any?, clip: InkClip, from: PageEntity, left: Float, top: Float,
    to: PageEntity, moved: MovedInk,
) = ui {
    // the editor that moved them may have closed: its last save comes first
    mover.saver.flush()
    val source = editorOrStored(from.id)
    if (source != null) {
        // the take is undone only if it is still the top step and the undo ran (not during a drag): else a paste
        source.layer.restoreTake(if (source === mover) take else null, clip, left, top)
        source.saver.request()
        source.saver.flush()
        // not stored on the source: the strokes stay on the target too rather than only in memory
        source.saver.error.value?.let { throw IllegalStateException("source page not saved", it) }
    } else {
        // no editor on the source page: add them to the stored page (an editor opening meanwhile reads it after this)
        val back = InkAffine.translate(left, top)
        PageWrites.around(from.id) { graph.repository.appendInk(from, clip.strokes.map(back::apply), clip.objects.map { it.transformed(back) }) }
    }
    var target = editorOrStored(to.id)
    if (target != null) {
        // the masks' cards follow them back before the target's save would detach them
        graph.repository.repointMasks(to.id, from.id, moved.masks.entries.associate { (old, new) -> new to old })
        // that write suspended: the editor may have closed (its last save kept the strokes) or the page opened again
        target = editorOrStored(to.id)
    }
    if (target != null) target.layer.removeByKeys(moved.keys, moved.objectIds)
    // the masks repointed above are not repointed twice: their refs no longer name the target
    else PageWrites.around(to.id) { graph.repository.takeBackFromPage(to.id, moved.keys, moved.objectIds, from.id, moved.masks) }
}
