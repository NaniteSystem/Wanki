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

    /** The real analyser reads 日本 as ニッポン and splits numbers; the word list still takes every usual spelling. */
    @Test
    fun realReadingsAcceptOtherSpellings() {
        val m = KuromojiMorphology()
        fun accepts(text: String, vararg typed: String) {
            val units = PieceWords.build(text, "ja", Words.Spaced, m, emptyMap()).map { it.unit() }
            for (t in typed) {
                val r = TypingMatcher(units).update(t)
                assertTrue(r.finished && r.units.all { it.state == UnitState.OK }, "$t for $text: $units")
            }
        }
        accepts("日本", "にほん", "にっぽん", "日本")
        accepts("二十三", "23", "二十三", "にじゅうさん")
        accepts("3月", "3月", "三月", "さんがつ")
        accepts("三日", "みっか", "3日")
        accepts("2026年", "にせんにじゅうろくねん", "二〇二六年")
    }
}
