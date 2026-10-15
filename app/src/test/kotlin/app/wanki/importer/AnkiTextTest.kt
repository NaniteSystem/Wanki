package app.wanki.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class AnkiTextTest {
    @Test
    fun `cleans html and anki markup`() {
        assertEquals("日本", AnkiText.clean("<div><b>日本</b></div>"))
        assertEquals("кошка\ncat", AnkiText.clean("кошка<br>cat [sound:cat.mp3]"))
        assertEquals("a & b <c>", AnkiText.clean("a &amp; b&nbsp;&lt;c&gt;"))
        assertEquals("é ✓", AnkiText.clean("&#233; &#x2713;"))
        assertEquals("one\ntwo", AnkiText.clean("<div>one</div>\n\n<div>two</div>"))
    }

    @Test
    fun `deck names and language guess`() {
        assertEquals("Japanese::Kanji", AnkiText.deckName("Japanese\u001FKanji"))
        assertEquals("Japanese::Kanji", AnkiText.deckName("Japanese::Kanji"))
        assertEquals("ja", AnkiText.guessLanguage(listOf("ねこ", "日本語")))
        assertEquals("zh", AnkiText.guessLanguage(listOf("中国", "学习")))
        assertEquals("ko", AnkiText.guessLanguage(listOf("한국어")))
        assertEquals("ru", AnkiText.guessLanguage(listOf("кошка", "собака")))
        assertEquals("en", AnkiText.guessLanguage(listOf("cat")))
    }
}
