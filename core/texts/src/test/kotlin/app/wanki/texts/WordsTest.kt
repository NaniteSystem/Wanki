package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordsTest {
    private fun words(s: String) = Words.Spaced.words(s).map { s.substring(it.start, it.end) }
    @Test fun punctuationIsNotAWord() = assertEquals(listOf("Мороз", "и", "солнце", "день", "чудесный"), words("Мороз и солнце; день чудесный!"))
    @Test fun innerApostropheAndHyphenStay() = assertEquals(listOf("don't", "well-known", "кто-то"), words("don't well-known, кто-то."))
    @Test fun arabicMarksStayInsideWords() = assertEquals(listOf("بِسْمِ", "اللَّهِ", "الرَّحْمَٰنِ"), words("بِسْمِ اللَّهِ الرَّحْمَٰنِ"))
    @Test fun dictionaryLanguages() { assertTrue(Words.needsDictionary("ja")); assertTrue(Words.needsDictionary("zh")); assertFalse(Words.needsDictionary("ru")) }
    @Test fun rtlLanguages() { assertTrue(Words.isRtl("ar")); assertFalse(Words.isRtl("ja")) }
}
