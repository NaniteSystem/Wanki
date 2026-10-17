package app.wanki.catalog

import app.wanki.data.AnswerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogFormatTest {
    @Test
    fun rowsUnescapeNewlinesTabsAndBackslashes() {
        val rows = CatalogFormat.parseRows("日\tдень\\nон: ニチ\t日\tkanji grade1\na\\tb\tc\\\\d\te\t\n")
        assertEquals(CatalogRow("日", "день\nон: ニチ", "日", "kanji grade1"), rows[0])
        assertEquals(CatalogRow("a\tb", "c\\d", "e", ""), rows[1])
    }

    @Test
    fun catalogEntries() {
        val json = """{"format": 1, "decks": [{"id": "ja-kana-hiragana", "path": ["Японский", "Кана", "Хирагана"],
            "language": "ja", "mode": "HANDWRITING", "count": 71, "file": "ja-kana-hiragana.tsv",
            "description": "d", "source": "s", "license": "l", "version": "abc"}]}"""
        val d = CatalogFormat.parseCatalog(json).single()
        assertEquals(listOf("Японский", "Кана", "Хирагана"), d.path)
        assertEquals(AnswerMode.HANDWRITING, d.mode)
        assertEquals(71, d.count)
        assertEquals("abc", d.version)
    }
}
