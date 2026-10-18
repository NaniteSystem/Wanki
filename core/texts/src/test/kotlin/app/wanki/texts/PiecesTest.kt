package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiecesTest {
    @Test fun poemSplitsByNonBlankLines() =
        assertEquals(listOf("Мороз и солнце; день чудесный!", "Ещё ты дремлешь, друг прелестный —"),
            PieceSplitter.split("Мороз и солнце; день чудесный!\n\n  Ещё ты дремлешь, друг прелестный —  \n", TextKind.POEM))
    @Test fun proseSplitsBySentences() =
        assertEquals(listOf("Hello there.", "How are you?", "«Fine!» she said."),
            PieceSplitter.split("Hello there. How are you? «Fine!» she said.", TextKind.PROSE))
    @Test fun proseKeepsClosingQuoteWithSentence() =
        assertEquals(listOf("Он сказал: «Иди.»", "Я пошёл."), PieceSplitter.split("Он сказал: «Иди.» Я пошёл.", TextKind.PROSE))
    @Test fun cjkTerminatorsBreakWithoutSpaces() =
        assertEquals(listOf("今日は晴れです。", "散歩しましょう！"), PieceSplitter.split("今日は晴れです。散歩しましょう！", TextKind.PROSE))
    @Test fun arabicQuestionMarkBreaks() =
        assertEquals(listOf("ما اسمك؟", "اسمي علي."), PieceSplitter.split("ما اسمك؟ اسمي علي.", TextKind.PROSE))
    @Test fun newlineAlwaysBreaksProse() =
        assertEquals(listOf("Первая строка", "Вторая"), PieceSplitter.split("Первая строка\nВторая", TextKind.PROSE))
    @Test fun emptyAndPunctuationOnlyGiveNothing() {
        assertTrue(PieceSplitter.split("  \n \n", TextKind.POEM).isEmpty())
        assertTrue(PieceSplitter.split("— … !", TextKind.PROSE).isEmpty())
    }
}
