package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals

class CopybookTest {
    @Test
    fun charsFollowTheTextAndSkipPunctuationAndSpaces() {
        assertEquals(listOf("春", "は", "あ", "け", "ぼ", "の"), Copybook.chars(listOf("春は、あけぼの。")))
        assertEquals(listOf("朝", "日"), Copybook.chars(listOf(" 朝「日」！ 1 2 ")))
    }

    @Test
    fun aRepeatedCharacterIsWrittenOnce() {
        assertEquals(listOf("人", "々", "の"), Copybook.chars(listOf("人々の人")))
    }

    @Test
    fun piecesAreWalkedInOrder() {
        assertEquals(listOf("山", "川", "空"), Copybook.chars(listOf("山川", "川空")))
    }

    @Test
    fun readingMarkupKeepsOnlyTheWrittenForm() {
        assertEquals(listOf("今", "日", "晴"), Copybook.chars(listOf("{今日|きょう}晴")))
    }

    @Test
    fun charactersWithoutAReferenceAreLeftOut() {
        val refs = setOf("水", "火")
        assertEquals(listOf("水", "火"), Copybook.chars(listOf("水と火"), practicable = { it in refs }))
    }

    @Test
    fun charactersOutsideTheBmpStayWhole() {
        // 𠮟 (U+20B9F) is a surrogate pair
        assertEquals(listOf("𠮟", "る"), Copybook.chars(listOf("𠮟る")))
    }

    @Test
    fun emptyOrSymbolOnlyTextHasNothingToWrite() {
        assertEquals(emptyList(), Copybook.chars(listOf("", "。、！？ 123")))
        assertEquals(emptyList(), Copybook.chars(emptyList()))
    }

    @Test
    fun tallySortsCharactersByTheirBestScore() {
        val t = Copybook.tally(listOf(80.0, 75.0, 60.0, null), total = 6, threshold = 75.0)
        assertEquals(CopybookTally(good = 2, weak = 1, failed = 1, skipped = 2), t)
        assertEquals(6, t.total)
    }

    @Test
    fun tallyOfAnUntouchedRunIsAllSkipped() {
        assertEquals(CopybookTally(0, 0, 0, 3), Copybook.tally(emptyList(), total = 3, threshold = 75.0))
        // a stale total never goes negative
        assertEquals(0, Copybook.tally(listOf(90.0), total = 0, threshold = 75.0).skipped)
    }
}
