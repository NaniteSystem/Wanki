package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TypingTextTest {
    private fun chars(s: String) = TypingText.fold(s).chars.toList()

    @Test
    fun punctuationAndSpacesAreIgnored() {
        assertEquals(chars("helloworld"), chars("Hello, world!"))
    }

    @Test
    fun katakanaFoldsToHiragana() {
        assertEquals(chars("にほん"), chars("ニホン"))
    }

    @Test
    fun fullWidthDigits() {
        assertEquals(chars("3"), chars("３"))
    }

    @Test
    fun arabicWithoutMarksMatches() {
        assertEquals(chars("بسم الله"), chars("بِسْمِ اللَّهِ"))
        assertEquals(chars("احمد"), chars("أحمد"))
    }

    @Test
    fun russianYoAndCase() {
        assertEquals(chars("елка"), chars("Ёлка"))
    }

    @Test
    fun sourceIndexPointsBack() {
        assertContentEquals(intArrayOf(0, 3), TypingText.fold("a, b").source)
    }

    @Test
    fun decomposedMarksComposeWithTheirBase() {
        assertEquals(chars("が"), chars("か\u3099"))
        assertEquals(chars("й"), chars("и\u0306"))
        assertEquals(chars("é"), chars("e\u0301"))
        assertContentEquals(intArrayOf(0, 2), TypingText.fold("か\u3099 x").source.let { intArrayOf(it[0], it[1]) })
    }

    @Test
    fun arabicHarakatStillDropped() {
        assertEquals(chars("كتب"), chars("كَتَبَ"))
    }
}
