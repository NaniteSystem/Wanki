package app.wanki.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.wanki.catalog.CatalogDeck
import app.wanki.catalog.CatalogInstaller
import app.wanki.data.DeckEntity
import app.wanki.graph
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.languageName

/** Notes shown above each language's decks. */
private val languageNotes = mapOf(
    "zh" to "Пока письмо по-китайски проверяется только распознаванием рукописи; эталоны черт появятся позже.",
    "ko" to "Пока письмо по-корейски проверяется только распознаванием рукописи; эталоны чамо появятся позже.",
)

/** The bundled ready-made decks, by language, with install / update. */
@Composable
fun CatalogScreen(onBack: () -> Unit, onOpenDeck: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val decks = remember { graph.catalog.decks }
    val installed by remember { graph.repository.observeInstalledCatalogDecks() }.collectAsState(emptyList())
    val byCatalogId = remember(installed) { installed.associateBy { it.catalogId } }
    val groups = remember(decks) { decks.groupBy { it.language } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Готовые колоды", onBack = onBack, subtitle = "${decks.size} колод · ${decks.sumOf { it.count }} карточек")
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).widthIn(max = 840.dp).testTag("catalog"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (language, list) ->
                item("lang-$language") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(languageName(language), style = MaterialTheme.typography.titleLarge)
                        languageNotes[language]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(list, key = { it.id }) { d ->
                    CatalogItem(d, byCatalogId[d.id], graph.catalogInstaller.states[d.id], { graph.catalogInstaller.install(d) }, onOpenDeck)
                }
            }
            item { Column(Modifier.padding(32.dp)) {} }
        }
    }
}

@Composable
private fun CatalogItem(
    d: CatalogDeck,
    installed: DeckEntity?,
    state: CatalogInstaller.State?,
    onInstall: () -> Unit,
    onOpenDeck: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.path.drop(1).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                    Text("${d.count} карт.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    state is CatalogInstaller.State.Running -> Text("${state.done} / ${state.total}", style = MaterialTheme.typography.labelLarge)
                    installed == null -> Button(onClick = onInstall, modifier = Modifier.testTag("install-${d.id}")) { Text("Добавить") }
                    installed.catalogVersion != d.version -> Button(onClick = onInstall) { Text("Обновить") }
                    else -> OutlinedButton(onClick = { onOpenDeck(installed.id) }) { Text("✓ Открыть") }
                }
            }
            if (state is CatalogInstaller.State.Running) {
                LinearProgressIndicator(progress = { state.done / state.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            Text(d.description, style = MaterialTheme.typography.bodySmall)
            Text("${d.source} · ${d.license}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state) {
                is CatalogInstaller.State.Done -> Text(
                    when {
                        state.added > 0 && state.updated > 0 -> "Добавлено карточек: ${state.added}, обновлено: ${state.updated}"
                        state.added > 0 -> "Добавлено карточек: ${state.added}"
                        state.updated > 0 -> "Обновлено карточек: ${state.updated}"
                        else -> "Колода уже актуальна"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                is CatalogInstaller.State.Failed -> Text("Не удалось: ${state.message}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }
}
