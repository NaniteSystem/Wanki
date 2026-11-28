package app.wanki.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.wanki.R
import app.wanki.data.ListSort
import app.wanki.data.Settings
import app.wanki.data.TrashKind
import app.wanki.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A list card that opens on tap and shows [menu] on long press; a pin icon marks pinned cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListCard(
    tag: String,
    pinned: Boolean,
    menu: List<HeaderAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        // a paper sheet: a shade lighter than the page under it, with a soft shadow
        Card(
            Modifier.fillMaxWidth().testTag(tag).combinedClickable(onClick = onClick, onLongClick = { open = true }),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Box { content() }
        }
        if (pinned) {
            Icon(
                painterResource(R.drawable.ic_keep), contentDescription = "Закреплено",
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        ItemMenu(expanded = open, onDismiss = { open = false }, items = menu)
    }
}

/** The menu of a card: rename, [settings] (notebooks only), duplicate, pin/unpin, delete. */
fun itemMenu(
    pinned: Boolean,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onSettings: (() -> Unit)? = null,
): List<HeaderAction> = listOfNotNull(
    HeaderAction(R.drawable.ic_edit, "Переименовать", "rename") { onRename() },
    onSettings?.let { HeaderAction(R.drawable.ic_settings, "Настройки", "settings") { it() } },
    HeaderAction(R.drawable.ic_content_copy, "Дублировать", "duplicate") { onDuplicate() },
    HeaderAction(R.drawable.ic_keep, if (pinned) "Открепить" else "Закрепить", "pin") { onPin() },
    HeaderAction(R.drawable.ic_delete, "Удалить", "delete") { onDelete() },
)

/** Header menu entries «Изменено / Название / Создано»; the current one is checked. */
fun sortMenu(settings: Settings, section: String): List<HeaderAction> {
    val current = settings.listSort(section)
    return ListSort.entries.map { s ->
        val name = when (s) { ListSort.EDITED -> "Изменено"; ListSort.TITLE -> "Название"; ListSort.CREATED -> "Создано" }
        HeaderAction(R.drawable.ic_sort, (if (s == current) "✓ " else "") + name, "menu-sort-${s.name}") {
            settings.setListSort(section, s)
        }
    }
}

/** Runs a card action and says so when it fails. */
fun CoroutineScope.listAction(undo: Undo, failure: String, block: suspend () -> Unit) {
    launch { runCatching { block() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; undo.say(failure) } }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.testTag("rename-field")) },
        confirmButton = {
            TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("rename-ok")) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Pin, duplicate and rename through the repository; [repository] errors surface as a snackbar. */
class ListActions(private val repository: Repository, private val undo: Undo, private val scope: CoroutineScope) {
    fun pin(kind: TrashKind, id: String, pinned: Boolean) = scope.listAction(undo, "Не удалось закрепить") { repository.setPinned(kind, id, pinned) }
    fun duplicateNotebook(id: String) = scope.listAction(undo, "Не удалось дублировать") { repository.duplicateNotebook(id) }
    fun duplicateNote(id: String) = scope.listAction(undo, "Не удалось дублировать") { repository.duplicateNote(id) }
    fun renameNotebook(id: String, title: String) = scope.listAction(undo, "Не удалось переименовать") { repository.renameNotebook(id, title) }
    fun renameNote(id: String, title: String) = scope.listAction(undo, "Не удалось переименовать") { repository.renameNote(id, title) }
}
