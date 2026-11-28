package app.wanki.search

import app.wanki.data.CardEntity
import app.wanki.data.CardWithNote
import app.wanki.data.DeckEntity
import app.wanki.data.DeckTree
import app.wanki.data.NoteEntity
import app.wanki.data.PageSearchHit
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.data.TextSearchHit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalSearchTest {
    private fun hit(group: SearchGroup, key: String) = SearchHit(group, key, key, "", SearchTarget.Card(key))

    private fun page(kind: String, id: String = "p", date: Long? = null, text: String = "кот") =
        PageSearchHit(id, "n1", "Конспект", 2, date, text, kind)

    @Test
    fun sectionsFollowGroupOrderAndSkipEmptyGroups() = runTest {
        val search = GlobalSearch(
            listOf(
                SearchSource("a") { listOf(hit(SearchGroup.DIARY, "d"), hit(SearchGroup.CARDS, "c1")) },
                SearchSource("b") { listOf(hit(SearchGroup.TEXTS, "t"), hit(SearchGroup.CARDS, "c2")) },
            ),
        )
        val r = search.search("кот")
        assertEquals(listOf(SearchGroup.CARDS, SearchGroup.TEXTS, SearchGroup.DIARY), r.sections.map { it.group })
        assertEquals(listOf("c1", "c2"), r.sections[0].hits.map { it.key })
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun blankQueryAsksNoSource() = runTest {
        var asked = false
        val search = GlobalSearch(listOf(SearchSource("a") { asked = true; listOf(hit(SearchGroup.CARDS, "c")) }))
        assertTrue(search.search("   ").isEmpty)
        assertFalse(asked)
    }

    @Test
    fun sourcesGetTheTrimmedQuery() = runTest {
        var got: String? = null
        GlobalSearch(listOf(SearchSource("a") { q -> got = q; emptyList() })).search("  кот \n")
        assertEquals("кот", got)
    }

    @Test
    fun aFailingSourceIsReportedAndTheOthersStillAnswer() = runTest {
        val search = GlobalSearch(
            listOf(
                SearchSource("Тексты") { throw IllegalStateException("база закрыта") },
                SearchSource("Карточки") { listOf(hit(SearchGroup.CARDS, "c")) },
            ),
        )
        val r = search.search("кот")
        assertEquals(listOf("Тексты: база закрыта"), r.failures)
        assertEquals(listOf(SearchGroup.CARDS), r.sections.map { it.group })
    }

    @Test
    fun aGroupKeepsPerGroupHitsAndCountsTheRest() {
        val search = GlobalSearch(emptyList(), perGroup = 2)
        val s = search.group((1..5).map { hit(SearchGroup.CARDS, "c$it") }).single()
        assertEquals(listOf("c1", "c2"), s.hits.map { it.key })
        assertEquals(3, s.more)
    }

    @Test
    fun duplicatesInAGroupAreShownOnce() {
        val s = GlobalSearch(emptyList()).group(listOf(hit(SearchGroup.CARDS, "c"), hit(SearchGroup.CARDS, "c"), hit(SearchGroup.TEXTS, "c")))
        assertEquals(listOf(1, 1), s.map { it.hits.size })
    }

    @Test
    fun pageHitsAreGroupedByTheirNotebookKind() {
        assertEquals(SearchGroup.NOTEBOOKS, GlobalSearch.groupOf(page("NOTEBOOK")))
        assertEquals(SearchGroup.DIARY, GlobalSearch.groupOf(page("DIARY")))
        assertEquals(SearchGroup.BOARDS, GlobalSearch.groupOf(page("BOARD")))
    }

    @Test
    fun pageSourceTriesCaseVariantsAndKeepsEachPageOnce() = runTest {
        val asked = mutableListOf<String>()
        val source = SearchSources.pages { q ->
            asked += q
            // SQLite LIKE folds only ASCII: «Кот» is found by the title-case variant only
            when (q) {
                "кот" -> listOf(page("NOTEBOOK", "p1"), page("DIARY", "p2", date = 20_000))
                "Кот" -> listOf(page("NOTEBOOK", "p1"), page("BOARD", "p3"))
                else -> emptyList()
            }
        }
        val hits = source.find("кот")
        assertEquals(listOf("кот", "КОТ", "Кот"), asked)
        assertEquals(listOf("p1", "p2", "p3"), hits.map { it.key })
        assertEquals(listOf(SearchGroup.NOTEBOOKS, SearchGroup.DIARY, SearchGroup.BOARDS), hits.map { it.group })
    }

    @Test
    fun pageHitTitles() {
        assertEquals("Конспект · стр. 3", SearchSources.pageHit(page("NOTEBOOK"), "кот").title)
        // diary pages are named by their day (epoch day 0 = 1 January 1970)
        assertEquals("1 января 1970", SearchSources.pageHit(page("DIARY", date = 0), "кот").title)
        assertEquals("Конспект", SearchSources.pageHit(page("BOARD"), "кот").title)
        val h = page("NOTEBOOK")
        assertEquals(SearchTarget.Page(h), SearchSources.pageHit(h, "кот").target)
    }

    @Test
    fun decksMatchByNameIgnoringCaseAndYo() {
        val tree = DeckTree(
            listOf(
                DeckEntity("a", name = "Японский"),
                DeckEntity("b", parentId = "a", name = "Ёжики"),
                DeckEntity("c", name = "Английский"),
            ),
        )
        val hits = SearchSources.deckHits(tree, "ежик")
        assertEquals(listOf("b"), hits.map { it.key })
        assertEquals("Японский › Ёжики", hits.single().title)
        assertEquals(SearchTarget.Deck("b"), hits.single().target)
        assertEquals(listOf("c", "a"), SearchSources.deckHits(tree, "СКИЙ").map { it.key })
    }

    @Test
    fun cardHitShowsQuestionAnswerAndDeck() {
        val tree = DeckTree(listOf(DeckEntity("d", name = "Кандзи")))
        val c = CardWithNote(CardEntity("c1", noteId = "n", deckId = "d"), NoteEntity("n", fields = "<b>日</b>\u001Fдень; солнце"))
        val h = SearchSources.cardHit(c, tree, "день")
        assertEquals("日", h.title)
        assertEquals("день; солнце · Кандзи", h.detail)
        assertEquals(SearchTarget.Card("c1"), h.target)
        assertEquals(SearchGroup.CARDS, h.group)
    }

    @Test
    fun cardFoundByATagShowsWhy() {
        val tree = DeckTree(emptyList())
        val c = CardWithNote(CardEntity("c1", noteId = "n", deckId = "x"), NoteEntity("n", fields = "日\u001Fдень", tags = " природа "))
        assertTrue(SearchSources.cardHit(c, tree, "природа").detail.contains("природа"))
    }

    @Test
    fun textHitShowsTheMatchingPieceWithoutMarkup() {
        val text = TextEntity("t", title = "Стихи", language = "ja")
        val piece = TextPieceEntity("p", textId = "t", pieceIndex = 4, content = "{今日|きょう}は晴れ")
        val h = SearchSources.textHit(TextSearchHit(text, piece), "晴れ")
        assertEquals("Стихи", h.title)
        assertEquals("5: 今日は晴れ", h.detail)
        assertEquals(SearchTarget.Text("t"), h.target)
        assertEquals("", SearchSources.textHit(TextSearchHit(text, null), "Стихи").detail)
    }

    @Test
    fun snippetCentresTheMatchOnOneLine() {
        val text = "начало\n" + "а".repeat(200) + " кот " + "б".repeat(200)
        val s = SearchMatch.snippet(text, "КОТ", width = 40)
        assertTrue(s.startsWith("…") && s.endsWith("…"), s)
        assertTrue(s.contains("кот"), s)
        assertFalse(s.contains("\n"))
        assertTrue(s.length <= 42, s)
    }

    @Test
    fun snippetWithoutAMatchIsTheStart() {
        assertEquals("короткий текст", SearchMatch.snippet("короткий\n  текст", "zzz"))
        assertEquals("абв…", SearchMatch.snippet("абвгд", "zzz", width = 3))
        // ё = е, as the page highlight
        assertEquals("ёлка", SearchMatch.snippet("ёлка", "елка"))
    }
}
