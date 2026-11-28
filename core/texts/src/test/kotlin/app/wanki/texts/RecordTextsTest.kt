package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordTextsTest {
    @Test
    fun noteBlocksKeepTheirOrderAndTheirText() {
        val r = RecordTexts.build(
            " Бородино ",
            listOf(RecordPart("Скажи-ка, дядя, ведь недаром  \n\n"), RecordPart("  "), RecordPart("Москва, спалённая пожаром,\nФранцузу отдана?")),
            hasInk = false,
        )
        assertEquals(
            RecordText.Ready("Бородино", "Скажи-ка, дядя, ведь недаром\nМосква, спалённая пожаром,\nФранцузу отдана?", TextKind.POEM),
            r,
        )
    }

    @Test
    fun innerTextStaysAsWritten() {
        // inner blank lines and leading spaces are the record's; only edges and line ends are trimmed
        assertEquals("  a\n\nb", RecordTexts.join(listOf(RecordPart("\n  a\n\nb  \n"))))
    }

    @Test
    fun pagePartsAreReadTopToBottomThenLeftToRight() {
        val parts = listOf(
            RecordPart("третья", top = 300f),
            RecordPart("вторая справа", top = 100f, left = 400f),
            RecordPart("первая", top = 20f),
            RecordPart("вторая слева", top = 100f, left = 10f),
        )
        assertEquals("первая\nвторая слева\nвторая справа\nтретья", RecordTexts.join(parts))
    }

    @Test
    fun equalPlacesKeepTheirOrder() {
        assertEquals("b\na", RecordTexts.join(listOf(RecordPart("b"), RecordPart("a"))))
    }

    @Test
    fun nothingWrittenIsEmpty() {
        assertEquals(RecordText.Empty, RecordTexts.build("Заголовок", emptyList(), hasInk = false))
        assertEquals(RecordText.Empty, RecordTexts.build("", listOf(RecordPart("  \n "), RecordPart("* * *")), hasInk = false))
    }

    @Test
    fun handwritingWithoutTextIsUnrecognized() {
        assertEquals(RecordText.Unrecognized, RecordTexts.build("", listOf(RecordPart("")), hasInk = true))
    }

    @Test
    fun recognizedHandwritingIsEnough() {
        val r = RecordTexts.build("", listOf(RecordPart("Сегодня я выучил новое слово.")), hasInk = true)
        assertEquals(RecordText.Ready("", "Сегодня я выучил новое слово.", TextKind.PROSE), r)
    }

    @Test
    fun kindIsGuessedFromLineLengths() {
        assertEquals(TextKind.PROSE, RecordTexts.kind("Одна строка."))
        assertEquals(TextKind.POEM, RecordTexts.kind("Я помню чудное мгновенье:\nПередо мной явилась ты,\n\nКак мимолётное виденье,"))
        val paragraph = "Это длинный абзац прозы, в котором много слов и несколько предложений подряд, без переносов строк. " +
            "Второе предложение тоже длинное."
        assertEquals(TextKind.PROSE, RecordTexts.kind("$paragraph\n$paragraph"))
        // one long paragraph among short lines is prose
        assertEquals(TextKind.PROSE, RecordTexts.kind("Коротко.\nКоротко.\n$paragraph"))
        // separators without letters do not count as lines
        assertEquals(TextKind.PROSE, RecordTexts.kind("Одна строка.\n* * *"))
    }

    @Test
    fun theResultSplitsLikeAnyText() {
        val r = RecordTexts.build("", listOf(RecordPart("Строка один\nСтрока два")), hasInk = false) as RecordText.Ready
        assertEquals(listOf("Строка один", "Строка два"), PieceSplitter.split(r.content, r.kind))
    }
}
