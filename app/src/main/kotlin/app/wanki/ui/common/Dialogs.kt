package app.wanki.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wanki.data.DeckEntity
import app.wanki.data.DeckTree

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }, modifier = Modifier.testTag("confirm-ok")) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Picks a deck from the tree (indented by depth). [allowNone] adds a "none" row, e.g. for
 * "no parent deck"; [exclude] hides decks that may not be chosen.
 */
@Composable
fun DeckPickerDialog(
    tree: DeckTree,
    title: String,
    selected: String?,
    onPick: (DeckEntity?) -> Unit,
    onDismiss: () -> Unit,
    allowNone: String? = null,
    exclude: Set<String> = emptySet(),
) {
    val decks = remember(tree, exclude) { tree.flat().filter { it.deck.id !in exclude } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (allowNone != null) item {
                    PickRow(allowNone, selected == null, 0) { onPick(null) }
                }
                items(decks, key = { it.deck.id }) { p ->
                    PickRow(p.deck.name, p.deck.id == selected, p.depth) { onPick(p.deck) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PickRow(label: String, selected: Boolean, depth: Int, onClick: () -> Unit) {
    Text(
        (if (selected) "● " else "") + label,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 16).dp, top = 10.dp, bottom = 10.dp),
    )
}

/** A text button that opens a small menu of actions. */
@Composable
fun MenuButton(label: String, items: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
            }
        }
    }
}
