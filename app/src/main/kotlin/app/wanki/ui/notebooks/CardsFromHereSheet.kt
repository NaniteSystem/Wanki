package app.wanki.ui.notebooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.wanki.data.CardWithNote
import app.wanki.data.NoteEntity

/** «Карточки отсюда»: the cards made on a page; a tap opens the card editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsFromHereSheet(cards: List<CardWithNote>, onOpen: (cardId: String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Карточки отсюда (${cards.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.testTag("cards-here")) {
            items(cards, key = { it.card.id }) { c ->
                val fields = c.note.fields.split(NoteEntity.FIELD_SEPARATOR)
                ListItem(
                    headlineContent = { Text(fields.getOrElse(0) { "" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(fields.getOrElse(1) { "" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onOpen(c.card.id) },
                )
            }
        }
    }
}
