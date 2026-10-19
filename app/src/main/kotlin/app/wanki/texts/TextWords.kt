package app.wanki.texts

import android.icu.text.BreakIterator
import android.icu.util.ULocale

/** Word breaking for texts; Japanese and Chinese use Android's dictionary-based ICU iterator. */
object TextWords {
    fun breaker(language: String): WordBreaker {
        if (!Words.needsDictionary(language)) return Words.Spaced
        val locale = ULocale.forLanguageTag(language)
        return WordBreaker { text ->
            // BreakIterator is not thread-safe, so each call gets its own instance
            val it = BreakIterator.getWordInstance(locale)
            it.setText(text)
            val out = ArrayList<Word>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                if (it.ruleStatus != BreakIterator.WORD_NONE) out.add(Word(start, end))
                start = end
                end = it.next()
            }
            out
        }
    }
}
