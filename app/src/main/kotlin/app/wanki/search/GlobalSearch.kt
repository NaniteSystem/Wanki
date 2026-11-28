package app.wanki.search

import app.wanki.data.NotebookKind
import app.wanki.data.PageSearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Kinds of results of the app-wide search, in the order the search screen shows them. */
enum class SearchGroup(val title: String) {
    DECKS("Колоды"),
    CARDS("Карточки"),
    TEXTS("Тексты наизусть"),
    NOTEBOOKS("Тетради"),
    DIARY("Дневник"),
    BOARDS("Доски"),
}

/** What opening a result shows. Pages go through `Navigator.openSearchHit`, like the notebooks' own search. */
sealed interface SearchTarget {
    data class Deck(val id: String) : SearchTarget
    data class Card(val id: String) : SearchTarget
    data class Text(val id: String) : SearchTarget
    data class Page(val hit: PageSearchHit) : SearchTarget
}

/** One result: [title] and [detail] are plain text; [key] is unique within its group. */
data class SearchHit(
    val group: SearchGroup,
    val key: String,
    val title: String,
    val detail: String,
    val target: SearchTarget,
)

/** A group's results: at most [GlobalSearch.perGroup] are kept, [more] counts the rest. */
data class SearchSection(val group: SearchGroup, val hits: List<SearchHit>, val more: Int = 0)

/** [failures]: one line per source that failed («Тетради: …»); the other sources still answer. */
data class SearchResults(val query: String, val sections: List<SearchSection>, val failures: List<String> = emptyList()) {
    val isEmpty: Boolean get() = sections.isEmpty()

    companion object {
        val EMPTY = SearchResults("", emptyList())
    }
}

/**
 * One module's own search, wrapped: [name] is shown when it fails. [find] gets the trimmed, non-blank query and
 * returns hits in the module's own order.
 */
class SearchSource(val name: String, val find: suspend (query: String) -> List<SearchHit>)

/**
 * The app-wide search: asks every [SearchSource] at once (each module keeps its own matching — the card browser's
 * [app.wanki.data.CardQuery], the pages' recognised text, …) and groups what they return.
 */
class GlobalSearch(private val sources: List<SearchSource>, val perGroup: Int = 20) {
    suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults.EMPTY
        val outcomes = coroutineScope {
            sources.map { s ->
                async {
                    try {
                        Result.success(s.find(q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val hits = outcomes.flatMap { it.getOrNull().orEmpty() }
        val failures = sources.zip(outcomes).mapNotNull { (s, r) ->
            r.exceptionOrNull()?.let { e -> "${s.name}: ${e.message ?: e.javaClass.simpleName}" }
        }
        return SearchResults(q, group(hits), failures)
    }

    /** Sections in [SearchGroup] order; empty groups are left out, duplicates (same group and key) shown once. */
    fun group(hits: List<SearchHit>): List<SearchSection> {
        val byGroup = hits.distinctBy { it.group to it.key }.groupBy { it.group }
        return SearchGroup.entries.mapNotNull { g ->
            val list = byGroup[g] ?: return@mapNotNull null
            SearchSection(g, list.take(perGroup), (list.size - perGroup).coerceAtLeast(0))
        }
    }

    companion object {
        /** The group a page hit belongs to, by the kind of its notebook. */
        fun groupOf(hit: PageSearchHit): SearchGroup = when (hit.kind) {
            NotebookKind.DIARY.name -> SearchGroup.DIARY
            NotebookKind.BOARD.name -> SearchGroup.BOARDS
            else -> SearchGroup.NOTEBOOKS
        }
    }
}

/** Matching and excerpts for results whose module has no matcher of its own (deck names). */
object SearchMatch {
    /** Case-insensitive, ё = е — as the page search highlight ([app.wanki.recognition.LineMatch]). */
    fun fold(s: String): String = s.lowercase().replace('ё', 'е')

    fun matches(text: String, query: String): Boolean = query.isNotBlank() && fold(text).contains(fold(query.trim()))

    /**
     * About [width] characters of [text] around the first match of [query], on one line, with «…» where it was cut.
     * Without a match: the start of the text.
     */
    fun snippet(text: String, query: String, width: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val q = query.trim()
        // fold keeps the length for every letter but a few ligatures; fall back to a plain search when it does not
        val folded = fold(flat)
        val i = if (folded.length == flat.length && q.isNotEmpty()) folded.indexOf(fold(q)) else flat.indexOf(q, ignoreCase = true)
        if (i < 0 || q.isEmpty()) return if (flat.length <= width) flat else flat.take(width).trimEnd() + "…"
        val before = (width - q.length) / 3
        val start = (i - before).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length).coerceAtLeast(i + q.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}
