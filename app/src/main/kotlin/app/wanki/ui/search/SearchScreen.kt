package app.wanki.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wanki.graph
import app.wanki.search.GlobalSearch
import app.wanki.search.SearchHit
import app.wanki.search.SearchResults
import app.wanki.search.SearchSources
import app.wanki.ui.common.ScreenHeader
import app.wanki.ui.common.ui
import kotlinx.coroutines.delay

/**
 * The app-wide search: cards and decks, texts, notebook pages, diary days and boards, grouped by kind.
 * [initialQuery] comes from the route, so the query and its results are back after a result was opened;
 * [onQuery] remembers the query in the route.
 */
@Composable
fun SearchScreen(initialQuery: String, onBack: () -> Unit, onQuery: (String) -> Unit, onOpen: (SearchHit, query: String) -> Unit) {
    val graph = LocalContext.current.graph
    val search = remember { GlobalSearch(SearchSources.all(graph.repository, graph.texts)) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(SearchResults.EMPTY) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialQuery.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = SearchResults.EMPTY; onQuery(""); return@LaunchedEffect }
        delay(250)
        searching = true
        try {
            results = ui { search.search(query) }
            onQuery(query)
        } finally {
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Поиск", onBack = onBack, subtitle = "Карточки, тексты, тетради, дневник и доски")
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Что найти") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag("global-search"),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        // the previous results stay while the next query runs
        val shown = if (query.isBlank()) SearchResults.EMPTY else results
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.failures.forEach { f ->
                item { Text("Не удалось найти — $f", color = MaterialTheme.colorScheme.error) }
            }
            if (query.isBlank()) {
                item { Text("Ищет в вопросах и ответах карточек, названиях колод, текстах наизусть и в распознанном рукописном тексте", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (shown.query == query.trim() && shown.isEmpty && shown.failures.isEmpty()) {
                item { Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            shown.sections.forEach { s ->
                item(key = "group-${s.group}") {
                    Text(
                        s.group.title + " · " + (s.hits.size + s.more),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).testTag("search-group-${s.group.name}"),
                    )
                }
                items(s.hits, key = { "${it.group}-${it.key}" }) { h ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(h, shown.query) }.testTag("search-hit-${h.group.name}-${h.key}")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (h.detail.isNotBlank()) Text(h.detail, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (s.more > 0) item(key = "more-${s.group}") {
                    Text("и ещё ${s.more} — уточните запрос", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
