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
        assertEquals(listOf("二〇二六〇四三一一"), JapaneseNumbers.forms("202604311"))
    }
    @Test fun notANumber() = assertTrue(JapaneseNumbers.forms("日本").isEmpty())
    @Test fun surfaceIsNotItsOwnForm() = assertTrue("3" !in JapaneseNumbers.forms("3"))

    /** Every spelling of a number lists all the others: the mapping is symmetric. */
    @Test fun spellingsAreSymmetric() {
        val classes = listOf(
            setOf("3", "三"), setOf("10", "十", "一〇"), setOf("23", "二十三", "二三"), setOf("100", "百", "一〇〇"),
            setOf("2026", "二千二十六", "二〇二六"), setOf("10000", "一万", "一〇〇〇〇"),
        )
        for (c in classes) for (x in c) assertEquals(c - x, JapaneseNumbers.forms(x).toSet(), x)
    }
    @Test fun leadingZeroIsDigitByDigit() = assertEquals(setOf("〇三"), JapaneseNumbers.forms("03").toSet())

    @Test fun readings() {
        assertEquals("にじゅうさん", JapaneseNumbers.word("二十三")!!.reading)
        assertEquals("にじゅうさん", JapaneseNumbers.word("23")!!.reading)
        assertEquals("にせんにじゅうろく", JapaneseNumbers.word("2026")!!.reading)
        assertEquals("さんびゃく", JapaneseNumbers.word("300")!!.reading)
        assertEquals("いちまん", JapaneseNumbers.word("一万")!!.reading)
        assertEquals(null, JapaneseNumbers.word("日本"))
    }

    @Test fun counters() {
        fun r(n: String, c: String) = JapaneseNumbers.word(n, c)!!.reading
        assertEquals("さんがつ", r("3", "月"))
        assertEquals("しがつ", r("四", "月"))
        assertEquals("にせんにじゅうろくねん", r("2026", "年"))
        assertEquals("じゅっぷん", r("10", "分"))
        assertEquals("ごふん", r("5", "分"))
        assertEquals("みっか", r("3", "日"))
        assertEquals("はつか", r("20", "日"))
        assertEquals("じゅうよっか", r("14", "日"))
        assertEquals("にじゅういちにち", r("21", "日"))
        assertEquals("ひとり", r("1", "人"))
        assertEquals("さんにん", r("3", "人"))
        assertEquals("よじ", r("4", "時"))
        assertEquals("いっかい", r("1", "回"))
        assertEquals("ろっこ", r("6", "個"))
        assertEquals(null, JapaneseNumbers.word("3", "本棚"))
    }

    @Test fun counterForms() {
        val f = JapaneseNumbers.word("3", "月")!!.forms
        assertTrue("三月" in f); assertTrue("3月" in f); assertTrue("3がつ" in f)
        // a day of the month may also be read as a count of days
        assertTrue("いちにち" in JapaneseNumbers.word("一", "日")!!.forms)
    }
}
