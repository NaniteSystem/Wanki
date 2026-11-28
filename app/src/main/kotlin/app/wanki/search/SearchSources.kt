package app.wanki.search

import app.wanki.anki.CardHtml
import app.wanki.data.CardQuery
import app.wanki.data.CardWithNote
import app.wanki.data.DeckTree
import app.wanki.data.NoteEntity
import app.wanki.data.PageSearchHit
import app.wanki.data.Repository
import app.wanki.data.TextRepository
import app.wanki.data.TextSearchHit
import app.wanki.texts.ReadingMarkup
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The modules' own searches as [SearchSource]s; each keeps its matching rules, this only turns their rows into
 * [SearchHit]s.
 */
object SearchSources {
    /** Cards per search: enough for the shown ones and an «ещё N». */
    const val CARD_LIMIT = 100

    fun all(repository: Repository, texts: TextRepository): List<SearchSource> = listOf(
        decksAndCards(repository),
        SearchSource("Тексты") { q -> texts.search(q).map { textHit(it, q) } },
        pages(repository::searchPages),
    )

    /** Deck names and the card browser's own search ([CardQuery]: fields and tags, case variants). */
    fun decksAndCards(repository: Repository) = SearchSource("Колоды и карточки") { q ->
        val tree = repository.deckTree()
        val cards = repository.observeCards(CardQuery(text = q, limit = CARD_LIMIT)).first()
        deckHits(tree, q) + cards.map { cardHit(it, tree, q) }
    }

    /**
     * Pages of notebooks, diary days and boards, through their recognised text (`Repository.searchPages`, the
     * notebooks' search). Its LIKE folds only ASCII case, so the query's case variants are asked too, as the card
     * browser does.
     */
    fun pages(search: suspend (String) -> List<PageSearchHit>) = SearchSource("Тетради, дневник и доски") { q ->
        CardQuery.caseVariants(q).flatMap { search(it) }.distinctBy { it.pageId }.map { pageHit(it, q) }
    }

    fun deckHits(tree: DeckTree, query: String): List<SearchHit> =
        tree.byId.values.filter { SearchMatch.matches(it.name, query) }
            .map { it.id to tree.title(it.id) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
            .map { (id, title) -> SearchHit(SearchGroup.DECKS, id, title, "", SearchTarget.Deck(id)) }

    fun cardHit(c: CardWithNote, tree: DeckTree, query: String): SearchHit {
        val question = CardHtml.plain(c.note.field(0))
        val answer = CardHtml.plain(c.note.field(if (c.card.writeFieldOrd == 0) 1 else c.card.writeFieldOrd))
        // a match only in another field or the tags still shows why the card was found
        val shown = if (SearchMatch.matches(question, query) || SearchMatch.matches(answer, query)) answer
        else c.note.fields.split(NoteEntity.FIELD_SEPARATOR).joinToString(" · ") { CardHtml.plain(it) } + " · " + c.note.tags.trim()
        val deck = tree.byId[c.card.deckId]?.let { tree.title(it.id) }
        return SearchHit(
            SearchGroup.CARDS, c.card.id, question.ifBlank { "(пустой вопрос)" },
            listOfNotNull(SearchMatch.snippet(shown, query, 80).takeIf { it.isNotBlank() }, deck).joinToString(" · "),
            SearchTarget.Card(c.card.id),
        )
    }

    fun textHit(h: TextSearchHit, query: String): SearchHit {
        val piece = h.piece
        val detail = if (piece == null) "" else "${piece.pieceIndex + 1}: " + SearchMatch.snippet(ReadingMarkup.parse(piece.content).plain, query)
        return SearchHit(SearchGroup.TEXTS, h.text.id, h.text.title, detail, SearchTarget.Text(h.text.id))
    }

    fun pageHit(h: PageSearchHit, query: String): SearchHit {
        val group = GlobalSearch.groupOf(h)
        val title = when (group) {
            SearchGroup.DIARY -> h.date?.let { LocalDate.ofEpochDay(it).format(DAY) } ?: h.notebookTitle
            SearchGroup.BOARDS -> h.notebookTitle
            else -> "${h.notebookTitle} · стр. ${h.pageIndex + 1}"
        }
        return SearchHit(group, h.pageId, title, SearchMatch.snippet(h.recognizedText, query), SearchTarget.Page(h))
    }

    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
}
