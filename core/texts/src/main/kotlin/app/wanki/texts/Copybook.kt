package app.wanki.texts

/** How a copybook run went: characters written well, below the bar, not passed, and not written from memory at all. */
data class CopybookTally(val good: Int, val weak: Int, val failed: Int, val skipped: Int) {
    val total: Int get() = good + weak + failed + skipped
}

/**
 * Copybook practice over a text («Прописи»): the handwriting practice screen walks the characters of a piece
 * (or a whole text) one after another. Handwriting practice only — it never touches the text's schedule.
 */
object Copybook {
    /**
     * Characters of [contents] to write, in reading order, each once (a repeat is the same exercise again).
     * `{表記|よみ}` markup keeps only the written form; anything that is not a letter (spaces, punctuation,
     * digits, symbols) is dropped, and so is every character [practicable] rejects (no stroke reference).
     */
    fun chars(contents: List<String>, practicable: (String) -> Boolean = { true }): List<String> {
        val seen = LinkedHashSet<String>()
        for (content in contents) {
            val plain = ReadingMarkup.parse(content).plain
            var i = 0
            while (i < plain.length) {
                val cp = plain.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                val ch = String(Character.toChars(cp))
                if (ch !in seen && practicable(ch)) seen += ch
            }
        }
        return seen.toList()
    }

    /**
     * Tally of a run over [total] characters: [best] holds, per character written from memory, its best
     * handwriting score (null when no attempt passed). A score at or above [threshold] is good.
     */
    fun tally(best: Collection<Double?>, total: Int, threshold: Double): CopybookTally {
        val good = best.count { it != null && it >= threshold }
        val failed = best.count { it == null }
        val weak = best.size - good - failed
        return CopybookTally(good, weak, failed, (total - best.size).coerceAtLeast(0))
    }
}
