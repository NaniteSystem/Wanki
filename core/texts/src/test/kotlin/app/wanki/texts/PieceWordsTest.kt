package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PieceWordsTest {
    private fun build(text: String, lang: String, morph: Morphology? = null, over: Map<String, ReadingOverride> = emptyMap()) =
        PieceWords.build(text, lang, Words.Spaced, morph, over)

    @Test fun spacedLanguageUsesBreaker() {
        val text = "Мама мыла раму."
        val w = build(text, "ru")
        assertEquals(3, w.size)
        w.forEach { assertEquals(text.substring(it.start, it.end), it.reading) }
    }
    @Test fun overrideWinsLongestFirst() {
        val text = "日本語と日本"
        // a morphology that would split every character on its own
        val morph = Morphology { t -> t.indices.map { MorphToken(it, it + 1, null) } }
        val over = mapOf("日本" to ReadingOverride("にほん"), "日本語" to ReadingOverride("にほんご"))
        val w = build(text, "ja-JP", morph, over)
        assertEquals(listOf("日本語" to "にほんご", "と" to "と", "日本" to "にほん"), w.map { text.substring(it.start, it.end) to it.reading })
    }
    @Test fun morphologyReadingsBecomeHiragana() {
        val text = "全て"
        val w = build(text, "ja", { listOf(MorphToken(0, 2, "スベテ")) })
        assertEquals(1, w.size)
        assertEquals("すべて", w[0].reading)
        assertTrue("全て" in w[0].forms)
    }
    @Test fun punctuationTokensDropped() {
        val text = "はい、そう。"
        val morph = Morphology { listOf(MorphToken(0, 2, "ハイ"), MorphToken(2, 3, null), MorphToken(3, 5, "ソウ"), MorphToken(5, 6, null)) }
        assertEquals(listOf("はい", "そう"), build(text, "ja", morph).map { it.reading })
    }
    @Test fun jaWithoutMorphologyFallsBackToBreaker() {
        val text = "ab cd"
        val w = build(text, "ja", null)
        assertEquals(listOf("ab", "cd"), w.map { it.reading })
    }
    @Test fun numberFormsAdded() {
        val w = build("3 月", "ja", null)
        assertTrue("三" in w[0].forms)
    }
    @Test fun overridesApplyToSpacedLanguagesByExactSurface() {
        val w = build("Мама мыла", "ru", over = mapOf("Мама" to ReadingOverride("мáма", listOf("мама"))))
        assertEquals("мáма", w[0].reading)
        assertEquals("мыла", w[1].reading)
    }
    @Test fun unitMirrorsWord() = assertEquals(TypingUnit("あ", listOf("亜")), PieceWord(0, 1, "あ", listOf("亜")).unit())

    @Test fun codecRoundTrip() {
        val words = listOf(
            PieceWord(0, 3, "にほん", listOf("日本", "a,b", "c|d")),
            PieceWord(4, 5, "と", emptyList()),
            PieceWord(6, 8, "x\u001Ey\\z", listOf("q\u001Fr\u001Ds")),
        )
        assertEquals(words, PieceWords.decode(PieceWords.encode(words)))
        assertEquals(emptyList(), PieceWords.decode(""))
        assertEquals("", PieceWords.encode(emptyList()))
        val m = mapOf("日本" to ReadingOverride("にほん", listOf("二本", "a|b")), "と" to ReadingOverride("と"))
        assertEquals(m, PieceWords.decodeReadings(PieceWords.encodeReadings(m)))
        assertEquals(emptyMap(), PieceWords.decodeReadings(""))
    }
    @Test fun numberFormsOnlyForJapanese() = assertEquals(emptyList(), build("3", "ru")[0].forms)
    @Test fun supplementaryKanjiIsAWord() = assertEquals(1, build("\uD842\uDFB7", "ja", { listOf(MorphToken(0, 2, null)) }).size)
    @Test fun codecKeepsBackslashBeforeEscapeLetters() {
        val w = listOf(PieceWord(0, 1, "a\\e", listOf("\\f", "\\g")))
        assertEquals(w, PieceWords.decode(PieceWords.encode(w)))
    }
}
