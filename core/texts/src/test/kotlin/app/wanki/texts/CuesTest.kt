package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CuesTest {
    private fun hidden(level: Int, n: Int = 8, seed: Long = 42) =
        VanishingCues.display(level, n, seed).withIndex().filter { it.value == WordDisplay.HIDDEN }.map { it.index }.toSet()
    @Test fun levelZeroShowsEverything() = assertTrue(VanishingCues.display(0, 8, 42).all { it == WordDisplay.VISIBLE })
    @Test fun levelsHideAQuarterHalfThreeQuarters() = assertEquals(listOf(2, 4, 6), (1..3).map { hidden(it).size })
    @Test fun levelsAreNested() { assertTrue(hidden(2).containsAll(hidden(1))); assertTrue(hidden(3).containsAll(hidden(2))) }
    @Test fun sameSeedSameWords() = assertEquals(hidden(2, seed = 7), hidden(2, seed = 7))
    @Test fun oneWordIsHiddenAtLevelOne() = assertEquals(setOf(0), hidden(1, n = 1))
    @Test fun levelFourIsFirstLettersLevelFiveIsNothing() {
        assertTrue(VanishingCues.display(4, 5, 1).all { it == WordDisplay.FIRST_LETTER })
        assertTrue(VanishingCues.display(5, 5, 1).all { it == WordDisplay.HIDDEN })
    }
    @Test fun hintDropsMarks() { assertEquals("Ё", FirstLetters.hint("Ёлка")); assertEquals("ب", FirstLetters.hint("بِسْمِ")) }
    @Test fun matchIgnoresCaseAndYo() { assertTrue(FirstLetters.matches("е", "Ёлка")); assertTrue(FirstLetters.matches("м", "Мороз")) }
    @Test fun arabicVariantsMatch() {
        assertTrue(FirstLetters.matches("ا", "أَحَدٌ")); assertTrue(FirstLetters.matches("ا", "إِنَّا")); assertTrue(FirstLetters.matches("ا", "ٱللَّهُ"))
        assertTrue(FirstLetters.matches("ي", "ئ")); assertFalse(FirstLetters.matches("ب", "تَبَّتْ"))
    }
}
