package app.wanki.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardQueryTest {
    @Test
    fun emptyQueryListsAllLiveCards() {
        val q = CardQuery().select(0)
        assertTrue(q.sql.contains("WHERE c.deleted = 0 ORDER BY c.position"))
        assertEquals(emptyList(), q.args)
    }

    @Test
    fun textMatchesFieldsAndTagsInEveryCase() {
        val q = CardQuery(text = "кот").select(0)
        // кот, КОТ, Кот (lowercase equals the query, so 3 variants), each against fields and tags
        assertEquals(listOf("%кот%", "%кот%", "%КОТ%", "%КОТ%", "%Кот%", "%Кот%"), q.args)
    }

    @Test
    fun likeWildcardsInTheQueryAreEscaped() {
        assertEquals("100\\%\\_a\\\\b", CardQuery.escape("100%_a\\b"))
    }

    @Test
    fun decksAndFiltersBecomeConditions() {
        val q = CardQuery(deckIds = listOf("a", "b"), filter = CardFilter.DUE).count(42)
        assertTrue(q.sql.contains("c.deckId IN (?,?)"))
        assertTrue(q.sql.contains("c.due <= ?"))
        assertEquals(listOf<Any>("a", "b", 42L), q.args)
    }

    @Test
    fun emptyDeckListMatchesNothing() {
        assertTrue(CardQuery(deckIds = emptyList()).count(0).sql.contains("AND 0"))
    }

    @Test
    fun suspendedCardsOnlyInTheirFilter() {
        assertTrue(CardQuery(filter = CardFilter.NEW).select(0).sql.contains("c.suspended = 0"))
        assertTrue(CardQuery(filter = CardFilter.SUSPENDED).select(0).sql.contains("c.suspended = 1"))
        assertFalse(CardQuery(filter = CardFilter.ALL).select(0).sql.contains("suspended"))
    }
}
