package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuromojiMorphologyTest {
    @Test
    fun readings() {
        val text = "日本語を学びます"
        val tokens = KuromojiMorphology().tokens(text)
        val t = tokens.first { text.substring(it.start, it.end) == "日本語" }
        assertEquals("ニホンゴ", t.reading)
        assertTrue(tokens.all { it.start >= 0 && it.end <= text.length && it.start < it.end })
    }

    @Test
    fun allReadsSubete() {
        assertEquals("スベテ", KuromojiMorphology().tokens("全て").first().reading)
    }

    @Test
    fun symbolsAndUnknownHaveNoReading() {
        val m = KuromojiMorphology()
        assertNull(m.tokens("!!").first().reading)
        assertNull(m.tokens("zzqx").first().reading)
    }
}
