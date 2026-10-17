package app.wanki.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.wanki.data.AnswerMode
import app.wanki.data.DeckEntity
import app.wanki.data.DeckTree
import app.wanki.graph
import app.wanki.ui.common.ConfirmDialog
import app.wanki.ui.common.DeckPickerDialog
import app.wanki.ui.common.Languages
import app.wanki.ui.common.ScreenHeader
import kotlinx.coroutines.launch

/** Deck settings: name, parent, language, answer mode, daily limits; deleting the deck. Saved on leaving. */
@Composable
fun DeckOptionsScreen(deckId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val graph = LocalContext.current.graph
    val tree by remember { graph.repository.observeDeckTree() }.collectAsState(null)
    val t = tree ?: return
    val deck = t.byId[deckId] ?: return
    DeckOptions(deck, t, onBack, onDeleted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckOptions(deck: DeckEntity, tree: DeckTree, onBack: () -> Unit, onDeleted: () -> Unit) {
    val graph = LocalContext.current.graph
    var name by remember(deck.id) { mutableStateOf(deck.name) }
    var parentId by remember(deck.id) { mutableStateOf(deck.parentId) }
    var language by remember(deck.id) { mutableStateOf(deck.language) }
    var mode by remember(deck.id) { mutableStateOf(AnswerMode.valueOf(deck.defaultAnswerMode)) }
    var newPerDay by remember(deck.id) { mutableStateOf(deck.newPerDay.toString()) }
    var reviewsPerDay by remember(deck.id) { mutableStateOf(deck.reviewsPerDay.toString()) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var modeApplied by remember { mutableStateOf(false) }

    fun edited() = deck.copy(
        name = name.trim().ifBlank { deck.name },
        parentId = parentId,
        language = language,
        defaultAnswerMode = mode.name,
        newPerDay = newPerDay.toIntOrNull()?.coerceIn(0, 9999) ?: deck.newPerDay,
        reviewsPerDay = reviewsPerDay.toIntOrNull()?.coerceIn(0, 99999) ?: deck.reviewsPerDay,
    )

    fun saveAndClose() {
        val e = edited()
        if (e != deck) graph.appScope.launch { graph.repository.updateDeck(e) }
        onBack()
    }
    androidx.activity.compose.BackHandler { saveAndClose() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Настройки колоды", onBack = ::saveAndClose)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                Text("Внутри колоды: " + (parentId?.let { tree.title(it) } ?: "нет"), Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { picking = true }) { Text("Изменить") }
            }
            HorizontalDivider()
            Text("Язык", style = MaterialTheme.typography.titleSmall)
            Text(
                "Язык ответов: от него зависят распознавание рукописи и эталоны знаков.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Languages.forEach { (code, n) -> FilterChip(language == code, { language = code }, { Text(n) }) }
            }
            HorizontalDivider()
            Text("Ответ по умолчанию", style = MaterialTheme.typography.titleSmall)
            ModeChips(mode) { mode = it; modeApplied = false }
            OutlinedButton(
                enabled = !modeApplied,
                onClick = {
                    modeApplied = true
                    val e = edited()
                    graph.appScope.launch { graph.repository.setDeckMode(e, mode) }
                },
            ) { Text(if (modeApplied) "Применено ко всем карточкам" else "Применить ко всем карточкам колоды") }
            HorizontalDivider()
            Text("Лимиты на день", style = MaterialTheme.typography.titleSmall)
            Text(
                "Лимит колоды ограничивает и её вложенные колоды. Изучаемые карточки не ограничиваются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    newPerDay, { newPerDay = it.filter(Char::isDigit).take(4) },
                    label = { Text("Новых в день") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    reviewsPerDay, { reviewsPerDay = it.filter(Char::isDigit).take(5) },
                    label = { Text("Повторений в день") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Button(onClick = ::saveAndClose) { Text("Сохранить") }
            TextButton(onClick = { deleting = true }) { Text("Удалить колоду…", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (picking) {
        DeckPickerDialog(
            tree, "Поместить внутрь колоды", parentId,
            onPick = { parentId = it?.id; picking = false },
            onDismiss = { picking = false },
            allowNone = "Нет (верхний уровень)",
            exclude = tree.subtree(deck.id).toSet(),
        )
    }
    if (deleting) {
        val n = tree.row(deck.id)?.total ?: 0
        val subs = tree.subtree(deck.id).size - 1
        ConfirmDialog(
            "Удалить колоду?",
            "Будут удалены «${deck.name}»" + (if (subs > 0) ", вложенные колоды ($subs)" else "") + " и карточки в них ($n).",
            "Удалить",
            onConfirm = {
                graph.appScope.launch { graph.repository.deleteDeck(deck.id) }
                onDeleted()
            },
            onDismiss = { deleting = false },
        )
    }
}
