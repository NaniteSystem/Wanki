package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingMarkupTest {
    @Test fun parsesPageFormat() {
        val m = ReadingMarkup.parse("{皆|みな}さん、{3|3|三}{月|がつ}")
        assertEquals("皆さん、3月", m.plain)
        assertEquals(ReadingOverride("みな"), m.readings["皆"])
        assertEquals(ReadingOverride("3", listOf("三")), m.readings["3"])
        assertEquals(ReadingOverride("がつ"), m.readings["月"])
    }
    @Test fun emptyReadingMeansKana() {
        val m = ReadingMarkup.parse("{こと||事}")
        assertEquals("こと", m.plain)
        assertEquals(ReadingOverride("こと", listOf("事")), m.readings["こと"])
    }
    @Test fun firstReadingWins() {
        val m = ReadingMarkup.parse("{日|ひ}と{日|にち}")
        assertEquals("日と日", m.plain)
        assertEquals("ひ", m.readings["日"]?.reading)
    }
    @Test fun noMarkupUnchanged() {
        val m = ReadingMarkup.parse("Просто текст, 日本語.")
        assertEquals("Просто текст, 日本語.", m.plain)
        assertTrue(m.readings.isEmpty())
    }
    @Test fun hasOnlyForReadingPatterns() {
        assertTrue(ReadingMarkup.has("a {皆|みな} b"))
        assertFalse(ReadingMarkup.has("a {x} b"))
        assertFalse(ReadingMarkup.has("function() { return 1; }"))
        assertFalse(ReadingMarkup.has("plain"))
    }
    @Test fun proseBracesStayVerbatim() {
        val m = ReadingMarkup.parse("{日本|にほん}と{ return 1; }")
        assertEquals("日本と{ return 1; }", m.plain)
        assertEquals(setOf("日本"), m.readings.keys)
        assertEquals("{x}", ReadingMarkup.parse("{x}").plain)
    }
}
