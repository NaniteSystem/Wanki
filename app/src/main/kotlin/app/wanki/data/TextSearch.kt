package app.wanki.data

/** A text found by its title or a piece: [piece] is the first matching piece in reading order, null for a title-only match. */
data class TextSearchHit(val text: TextEntity, val piece: TextPieceEntity?)

/**
 * Search over texts learned by heart: titles and piece contents (stored `{表記|よみ}` markup included, so both the
 * written form and the reading match). Same LIKE rules as the card browser ([CardQuery]): the common case variants of
 * the query are tried, `%` and `_` are literal.
 */
object TextSearch {
    fun titles(query: String, limit: Int): CardQuery.Sql {
        val (where, args) = like("t.title", query)
        return CardQuery.Sql("SELECT t.* FROM texts t WHERE t.deleted = 0 AND $where ORDER BY t.position LIMIT $limit", args)
    }

    fun pieces(query: String, limit: Int): CardQuery.Sql {
        val (where, args) = like("p.content", query)
        return CardQuery.Sql(
            "SELECT p.* FROM text_pieces p JOIN texts t ON t.id = p.textId WHERE t.deleted = 0 AND $where " +
                "ORDER BY t.position, p.pieceIndex LIMIT $limit",
            args,
        )
    }

    /**
     * One hit per text, in the texts' list order: title matches and texts of [pieces] (whose texts are looked up in
     * [textsById]); each carries its first matching piece.
     */
    fun merge(titles: List<TextEntity>, pieces: List<TextPieceEntity>, textsById: Map<String, TextEntity>): List<TextSearchHit> {
        val firstPiece = LinkedHashMap<String, TextPieceEntity>()
        pieces.sortedBy { it.pieceIndex }.forEach { firstPiece.putIfAbsent(it.textId, it) }
        val texts = LinkedHashMap<String, TextEntity>()
        titles.forEach { texts[it.id] = it }
        firstPiece.keys.forEach { id -> textsById[id]?.let { texts.putIfAbsent(id, it) } }
        return texts.values
            .filter { !it.deleted }
            .sortedWith(compareBy<TextEntity> { it.position }.thenBy { it.id })
            .map { TextSearchHit(it, firstPiece[it.id]) }
    }

    private fun like(column: String, query: String): Pair<String, List<Any>> {
        val variants = CardQuery.caseVariants(query.trim()).map { "%" + CardQuery.escape(it) + "%" }
        return variants.joinToString(" OR ", "(", ")") { "$column LIKE ? ESCAPE '\\'" } to variants
    }
}
