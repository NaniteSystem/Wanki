package app.wanki.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class DelimitedTextTest {
    @Test
    fun tabSeparatedWithTags() {
        val rows = DelimitedText.parse("день\t日\tkanji n5\nкнига\t本\n")
        assertEquals(listOf(DelimitedText.Row("день", "日", "kanji n5"), DelimitedText.Row("книга", "本", "")), rows)
    }

    @Test
    fun separatorIsDetected() {
        assertEquals(';', DelimitedText.detectSeparator(listOf("a;b", "c;d", "e;f")))
        assertEquals(',', DelimitedText.detectSeparator(listOf("a,b", "c,d")))
        assertEquals('\t', DelimitedText.detectSeparator(listOf("a, b\tc", "d, e\tf")))
    }

    @Test
    fun quotedFieldsMayHoldSeparatorsQuotesAndNewlines() {
        val rows = DelimitedText.parse("\"cat, kitten\",\"кошка\"\n\"say \"\"hi\"\"\",\"line1\nline2\"\n")
        assertEquals("cat, kitten", rows[0].front)
        assertEquals("кошка", rows[0].back)
        assertEquals("say \"hi\"", rows[1].front)
        assertEquals("line1\nline2", rows[1].back)
    }

    @Test
    fun ankiExportHeaders() {
        val text = "#separator:tab\n#html:true\n#tags column:3\nдень<br>солнце\t日\tkanji\n"
        assertEquals(listOf(DelimitedText.Row("день\nсолнце", "日", "kanji")), DelimitedText.parse(text))
    }

    @Test
    fun blankLinesAndBomAreSkipped() {
        val rows = DelimitedText.parse("﻿a\tb\r\n\r\nc\td\r\n")
        assertEquals(listOf("a", "c"), rows.map { it.front })
    }
}
