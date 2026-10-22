package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JapaneseNumbersTest {
    @Test fun digitToKanji() = assertTrue("三" in JapaneseNumbers.forms("3"))
    @Test fun kanjiToDigit() = assertTrue("3" in JapaneseNumbers.forms("三"))
    @Test fun twoDigits() {
        val f = JapaneseNumbers.forms("23")
        assertTrue("二十三" in f); assertTrue("二三" in f)
    }
    @Test fun positionalKanjiBack() {
        assertTrue("23" in JapaneseNumbers.forms("二十三"))
        assertTrue("23" in JapaneseNumbers.forms("二三"))
        assertTrue("10" in JapaneseNumbers.forms("十"))
    }
    @Test fun longNumbersDigitByDigit() {
        val f = JapaneseNumbers.forms("2026")
        assertTrue("二〇二六" in f)
        assertEquals(listOf("二〇二六"), f)
    }
    @Test fun notANumber() = assertTrue(JapaneseNumbers.forms("日本").isEmpty())
    @Test fun surfaceIsNotItsOwnForm() = assertTrue("3" !in JapaneseNumbers.forms("3"))
}
