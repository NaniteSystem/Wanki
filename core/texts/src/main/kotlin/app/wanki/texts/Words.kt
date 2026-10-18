package app.wanki.texts

/** A word as `[start, end)` character offsets in its piece. */
data class Word(val start: Int, val end: Int)

/** Finds word boundaries; languages without spaces need a dictionary-based implementation. */
fun interface WordBreaker {
    fun words(text: String): List<Word>
}

object Words {
    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
    private val dictionaryLanguages = setOf("ja", "zh", "th")
    private val rtlLanguages = setOf("ar", "he", "fa", "ur")

    /** For languages that separate words with spaces; marks (harakat) stay inside words. */
    val Spaced = WordBreaker { text -> word.findAll(text).map { Word(it.range.first, it.range.last + 1) }.toList() }

    /** True for languages written without spaces, where [Spaced] cannot find words. */
    fun needsDictionary(language: String): Boolean = language in dictionaryLanguages

    fun isRtl(language: String): Boolean = language in rtlLanguages
}
