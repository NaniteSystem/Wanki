package app.wanki.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.wanki.data.DayTakenException
import app.wanki.data.NotebookKind
import app.wanki.data.Repository
import app.wanki.data.SourceKind
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Where a card's source is: a live page, a page in the trash, or nothing any more. */
sealed interface SourceTarget {
    data class Live(val route: Route) : SourceTarget
    data class Trashed(val kind: TrashKind, val id: String, val route: Route) : SourceTarget
    data object Gone : SourceTarget
}

/** The source of the card of [noteId]; null when it was not made from a page. */
suspend fun Repository.sourceTarget(noteId: String): SourceTarget? {
    // shortcut: only page refs open a source; other kinds get the item once they are made from the UI
    val ref = sourceOf(noteId)?.takeIf { it.kind == SourceKind.PAGE.name } ?: return null
    if (ref.detached) return SourceTarget.Gone
    val page = page(ref.sourceId) ?: return SourceTarget.Gone
    val nb = notebook(page.notebookId) ?: return SourceTarget.Gone
    val diary = nb.kind == NotebookKind.DIARY.name && page.date != null
    val area = if (ref.x != null && ref.y != null && ref.w != null && ref.h != null) "${ref.x},${ref.y},${ref.w},${ref.h}" else null
    val route = if (diary) Route.DiaryDay(page.date!!) else Route.Notebook(nb.id, page.id, area = area)
    return when {
        page.deleted -> SourceTarget.Trashed(if (diary) TrashKind.DIARY_DAY else TrashKind.PAGE, page.id, route)
        nb.deleted -> SourceTarget.Trashed(TrashKind.NOTEBOOK, nb.id, route)
        else -> SourceTarget.Live(route)
    }
}

/** «Открыть источник» for a card: its label and state, and the trash dialog it may show. */
class SourceOpener(val target: SourceTarget?, private val onOpen: (Route) -> Unit) {
    var trashed by mutableStateOf<SourceTarget.Trashed?>(null)
    val label get() = if (target == SourceTarget.Gone) "Источник удалён" else "Открыть источник"
    val enabled get() = target is SourceTarget.Live || target is SourceTarget.Trashed

    fun open() {
        when (val t = target) {
            is SourceTarget.Live -> onOpen(t.route)
            is SourceTarget.Trashed -> trashed = t
            else -> Unit
        }
    }
}

/** The opener for the card of [noteId]; a new id re-reads its source. */
@Composable
fun rememberSourceOpener(noteId: String?, onOpen: (Route) -> Unit): SourceOpener {
    val graph = LocalContext.current.graph
    val target by produceState<SourceTarget?>(null, noteId) {
        value = noteId?.let { ui { graph.repository.sourceTarget(it) } }
    }
    val opener = remember(target) { SourceOpener(target, onOpen) }
    // outside the dialog: closing it must not cancel the restore
    val undo = LocalUndo.current
    val scope = rememberUiScope()
    opener.trashed?.let { t ->
        AlertDialog(
            onDismissRequest = { opener.trashed = null },
            title = { Text("Страница в корзине") },
            text = { Text("Источник карточки удалён в корзину. Восстановить его?") },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("source-restore"), onClick = {
                    opener.trashed = null
                    scope.launch {
                        try {
                            graph.repository.restore(t.kind, t.id)
                            onOpen(t.route)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DayTakenException) {
                            undo.say("Этот день дневника уже занят")
                        } catch (e: Exception) {
                            undo.say("Не удалось восстановить")
                        }
                    }
                }) { Text("Восстановить и открыть") }
            },
            dismissButton = { TextButton(onClick = { opener.trashed = null }) { Text("Отмена") } },
        )
    }
    return opener
}
