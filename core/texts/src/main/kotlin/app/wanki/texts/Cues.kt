package app.wanki.texts

import java.text.Normalizer
import java.util.Random
import kotlin.math.ceil

enum class WordDisplay { VISIBLE, FIRST_LETTER, HIDDEN }

/** Progressive hiding: levels 1-3 hide 25/50/75% of the words, 4 shows first letters, 5 shows nothing. */
object VanishingCues {
    const val MAX_LEVEL = 5
    private val shares = doubleArrayOf(0.25, 0.5, 0.75)

    /** Same [seed] gives the same words; a higher level hides a superset of a lower one. */
    fun display(level: Int, wordCount: Int, seed: Long): List<WordDisplay> {
        val lvl = level.coerceIn(0, MAX_LEVEL)
        return when (lvl) {
            0 -> List(wordCount) { WordDisplay.VISIBLE }
            4 -> List(wordCount) { WordDisplay.FIRST_LETTER }
            5 -> List(wordCount) { WordDisplay.HIDDEN }
            else -> {
                val hiddenCount = ceil(shares[lvl - 1] * wordCount).toInt()
                val hidden = (0 until wordCount).shuffled(Random(seed)).take(hiddenCount).toSet()
                List(wordCount) { if (it in hidden) WordDisplay.HIDDEN else WordDisplay.VISIBLE }
            }
        }
    }
}

object FirstLetters {
    /** The first letter as stored (case kept), without the combining marks after it. */
    fun hint(word: String): String =
        if (word.isEmpty()) "" else String(Character.toChars(word.codePointAt(0)))

    /** Compares first base letters, ignoring case, marks and Arabic/Russian letter variants. */
    fun matches(typed: String, word: String): Boolean {
        val a = firstBase(typed)
        return a != null && a == firstBase(word)
    }

    private val mark = Regex("\\p{M}")

    private fun firstBase(s: String): Int? =
        normalize(s).codePoints().toArray().firstOrNull { Character.isLetterOrDigit(it) }

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in Normalizer.normalize(s, Normalizer.Form.NFD).replace(mark, "").lowercase()) {
            sb.append(LetterFolding.fold(c))
        }
        return sb.toString()
    }
}

/** Letter variants that count as the same letter when comparing: Russian ё, Greek final sigma, Arabic alef/yaa/waw/taa forms. */
object LetterFolding {
    fun fold(c: Char): String = when (c) {
        'ё' -> "е"
        'ς' -> "σ"
        'آ', 'أ', 'إ', 'ٱ' -> "ا"
        'ى', 'ئ' -> "ي"
        'ؤ' -> "و"
        'ة' -> "ه"
        'ـ' -> ""
        else -> c.toString()
    }
}
