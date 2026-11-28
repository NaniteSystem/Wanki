package app.wanki.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSearchTest {
    @Test
    fun titlesUseCaseVariantsAndEscapedWildcards() {
        val q = TextSearch.titles("кот_1", 50)
        assertTrue(q.sql.contains("t.deleted = 0"), q.sql)
        assertTrue(q.sql.endsWith("LIMIT 50"), q.sql)
        assertEquals(listOf("%кот\\_1%", "%КОТ\\_1%", "%Кот\\_1%"), q.args)
    }

    @Test
    fun piecesSkipDeletedTextsAndKeepReadingOrder() {
        val q = TextSearch.pieces(" кот ", 10)
        assertTrue(q.sql.contains("JOIN texts t ON t.id = p.textId WHERE t.deleted = 0"), q.sql)
        assertTrue(q.sql.contains("ORDER BY t.position, p.pieceIndex"), q.sql)
        assertEquals(3, q.args.size)
        assertEquals("%кот%", q.args[0])
    }

    private fun text(id: String, position: Long, deleted: Boolean = false) =
        TextEntity(id, title = id, language = "ru", position = position, deleted = deleted)

    private fun piece(id: String, textId: String, index: Int) = TextPieceEntity(id, textId = textId, pieceIndex = index, content = id)

    @Test
    fun oneHitPerTextWithItsFirstMatchingPieceInListOrder() {
        val a = text("a", 2)
        val b = text("b", 1)
        val c = text("c", 3)
        val hits = TextSearch.merge(
            titles = listOf(c),
            pieces = listOf(piece("a7", "a", 7), piece("a2", "a", 2), piece("b0", "b", 0)),
            textsById = mapOf("a" to a, "b" to b, "c" to c),
        )
        assertEquals(listOf("b", "a", "c"), hits.map { it.text.id })
        assertEquals("a2", hits[1].piece?.id)
        assertNull(hits[2].piece)
    }

    @Test
    fun aTitleMatchStillShowsAMatchingPiece() {
        val a = text("a", 1)
        val hits = TextSearch.merge(listOf(a), listOf(piece("a3", "a", 3)), mapOf("a" to a))
        assertEquals("a3", hits.single().piece?.id)
    }

    @Test
    fun piecesOfUnknownOrDeletedTextsAreDropped() {
        val gone = text("g", 1, deleted = true)
        val hits = TextSearch.merge(emptyList(), listOf(piece("x", "missing", 0), piece("g0", "g", 0)), mapOf("g" to gone))
        assertTrue(hits.isEmpty())
    }
}
