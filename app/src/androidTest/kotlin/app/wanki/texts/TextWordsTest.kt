package app.wanki.texts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWordsTest {
    @Test fun japaneseIsSplitIntoWords() {
        val s = "私は学生です。"
        val w = TextWords.breaker("ja").words(s).map { s.substring(it.start, it.end) }
        assertTrue(w.size >= 3); assertTrue("学生" in w); assertFalse("。" in w)
    }

    @Test fun russianUsesSpaces() = assertEquals(2, TextWords.breaker("ru").words("Мороз, солнце!").size)
}
