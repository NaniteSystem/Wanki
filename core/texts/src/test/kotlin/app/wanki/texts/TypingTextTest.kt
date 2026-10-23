package app.wanki.texts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertContentEquals(intArrayOf(0, 3), TypingText.fold("か\u3099 x").source.let { intArrayOf(it[0], it[1]) })
    }

    @Test
    fun arabicHarakatStillDropped() {
        assertEquals(chars("كتب"), chars("كَتَبَ"))
    }

    @Test
    fun greekFinalSigma() {
        assertEquals(chars("λογοσ"), chars("λογος"))
        assertEquals(chars("λογος"), chars("ΛΟΓΟΣ"))
    }

    /** Appending, deleting and replacing anywhere: the incremental fold always equals a fresh one. */
    @Test
    fun incrementalFoldEqualsFreshFold() {
        val rnd = Random(11)
        val pool = listOf("a", "b", "か", "\u3099", "\u0301", "и", "\u0306", ", ", " ", "\uD83D\uDE00", "ヶ", "３", "ё", "ـ", "ς")
        val inc = IncrementalFold()
        var s = ""
        var prev = IntArray(0)
        repeat(2000) {
            s = when (rnd.nextInt(4)) {
                0 -> if (s.isEmpty()) s else s.substring(0, rnd.nextInt(s.length))
                1 -> { val at = rnd.nextInt(s.length + 1); s.substring(0, at) + pool[rnd.nextInt(pool.size)] + s.substring(at) }
                else -> s + pool[rnd.nextInt(pool.size)]
            }
            val from = inc.update(s)
            val fresh = TypingText.fold(s)
            val got = inc.folded()
            assertContentEquals(fresh.chars, got.chars, s)
            assertContentEquals(fresh.source, got.source, s)
            // folded chars before the reported start are the previous ones
            assertTrue(from in 0..minOf(prev.size, fresh.chars.size))
            assertContentEquals(prev.copyOf(from), fresh.chars.copyOf(from))
            prev = fresh.chars
        }
    }
}
