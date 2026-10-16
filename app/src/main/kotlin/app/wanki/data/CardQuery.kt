package app.wanki.data

enum class CardFilter { ALL, NEW, LEARNING, REVIEW, DUE, SUSPENDED }

enum class CardSort { POSITION, DUE, QUESTION, ANSWER, CHANGED }

/**
 * Card browser search. Text matches fields and tags; SQLite's LIKE only folds ASCII case, so
 * the common case variants of the query are tried too (кот / Кот / КОТ).
 */
data class CardQuery(
    val text: String = "",
    /** null = every deck */
    val deckIds: List<String>? = null,
    val filter: CardFilter = CardFilter.ALL,
    val sort: CardSort = CardSort.POSITION,
    val limit: Int = 1000,
) {
    data class Sql(val sql: String, val args: List<Any>)

    fun select(now: Long): Sql {
        val (where, args) = where(now)
        val order = when (sort) {
            CardSort.POSITION -> "c.position, c.id"
            CardSort.DUE -> "c.lastReview IS NULL, c.due, c.position"
            CardSort.QUESTION -> "n.fields COLLATE NOCASE"
            CardSort.ANSWER -> "substr(n.fields, instr(n.fields, char(31)) + 1) COLLATE NOCASE"
            CardSort.CHANGED -> "c.updatedAt DESC"
        }
        return Sql(
            "SELECT $CARD_WITH_NOTE_COLUMNS FROM cards c JOIN notes n ON n.id = c.noteId WHERE $where ORDER BY $order LIMIT $limit",
            args,
        )
    }

    fun count(now: Long): Sql {
        val (where, args) = where(now)
        return Sql("SELECT COUNT(*) FROM cards c JOIN notes n ON n.id = c.noteId WHERE $where", args)
    }

    /** Ids of every matching card (for "select all"). */
    fun ids(now: Long): Sql {
        val (where, args) = where(now)
        return Sql("SELECT c.id FROM cards c JOIN notes n ON n.id = c.noteId WHERE $where", args)
    }

    private fun where(now: Long): Pair<String, List<Any>> {
        val parts = mutableListOf("c.deleted = 0")
        val args = mutableListOf<Any>()
        deckIds?.let { ids ->
            if (ids.isEmpty()) parts += "0" else {
                parts += "c.deckId IN (${ids.joinToString(",") { "?" }})"
                args.addAll(ids)
            }
        }
        val q = text.trim()
        if (q.isNotEmpty()) {
            val variants = caseVariants(q).map { "%" + escape(it) + "%" }
            parts += variants.joinToString(" OR ", "(", ")") { "n.fields LIKE ? ESCAPE '\\' OR n.tags LIKE ? ESCAPE '\\'" }
            variants.forEach { args += it; args += it }
        }
        when (filter) {
            CardFilter.ALL -> Unit
            CardFilter.NEW -> parts += "c.suspended = 0 AND c.lastReview IS NULL"
            CardFilter.LEARNING -> parts += "c.suspended = 0 AND c.lastReview IS NOT NULL AND c.state != 'REVIEW'"
            CardFilter.REVIEW -> parts += "c.suspended = 0 AND c.lastReview IS NOT NULL AND c.state = 'REVIEW'"
            CardFilter.DUE -> {
                parts += "c.suspended = 0 AND c.lastReview IS NOT NULL AND c.due <= ?"
                args += now
            }
            CardFilter.SUSPENDED -> parts += "c.suspended = 1"
        }
        return parts.joinToString(" AND ") to args
    }

    companion object {
        fun caseVariants(q: String): List<String> =
            listOf(q, q.lowercase(), q.uppercase(), q.lowercase().replaceFirstChar { it.titlecase() }).distinct()

        fun escape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
