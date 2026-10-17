package app.wanki.anki

import kotlin.test.Test
import kotlin.test.assertEquals

class AnkiTemplateTest {
    private val ctx = AnkiTemplate.Context(
        fields = mapOf("Front" to "日本[にほん]", "Back" to "Япония", "Extra" to "", "Text" to "{{c1::東京}}は{{c2::日本::страна}}の首都"),
        tags = "geo", deck = "Японский::Слова", noteType = "Basic", card = "Card 1",
    )

    @Test
    fun fieldsSectionsAndFrontSide() {
        val q = AnkiTemplate.render("{{Front}}{{#Extra}}<br>{{Extra}}{{/Extra}}{{^Extra}}!{{/Extra}}", ctx, question = true)
        assertEquals("日本[にほん]!", q)
        val a = AnkiTemplate.render("{{FrontSide}}<hr id=answer>{{Back}} ({{Subdeck}}, {{Tags}})", ctx, question = false, frontSide = q)
        assertEquals("日本[にほん]!<hr id=answer>Япония (Слова, geo)", a)
    }

    @Test
    fun furiganaFilters() {
        assertEquals("日本", AnkiTemplate.render("{{kanji:Front}}", ctx, true))
        assertEquals("にほん", AnkiTemplate.render("{{kana:Front}}", ctx, true))
        assertEquals("<ruby>日本<rt>にほん</rt></ruby>", AnkiTemplate.render("{{furigana:Front}}", ctx, true))
        assertEquals("Япония", AnkiTemplate.render("{{text:Back}}", ctx.copy(fields = ctx.fields + ("Back" to "<b>Япония</b>")), true))
    }

    @Test
    fun typingAndTtsAreLeftToTheApp() {
        assertEquals("", AnkiTemplate.render("{{type:Back}}{{tts ja_JP:Front}}", ctx, true))
        assertEquals("Япония", AnkiTemplate.render("{{type:Back}}", ctx, false))
    }

    @Test
    fun clozeShowsTheCardsDeletion() {
        val c1 = ctx.copy(ord = 0)
        assertEquals("<span class=\"cloze\">[…]</span>は日本の首都", AnkiTemplate.render("{{cloze:Text}}", c1, true))
        assertEquals("<span class=\"cloze\">東京</span>は日本の首都", AnkiTemplate.render("{{cloze:Text}}", c1, false))
        val c2 = ctx.copy(ord = 1)
        assertEquals("東京は<span class=\"cloze\">[страна]</span>の首都", AnkiTemplate.render("{{cloze:Text}}", c2, true))
        assertEquals(listOf("日本"), AnkiTemplate.clozeAnswers(ctx.fields.getValue("Text"), 2))
    }

    @Test
    fun fieldsUsedIgnoresFiltersAndFrontSide() {
        assertEquals(setOf("Back", "Extra", "Front"), AnkiTemplate.fieldsUsed("{{FrontSide}}{{#Extra}}{{Extra}}{{/Extra}}{{furigana:Back}} {{Front}}"))
    }

    @Test
    fun unknownFieldsRenderEmptyAndCaseIsForgiven() {
        assertEquals("[]", AnkiTemplate.render("[{{Missing}}]", ctx, true))
        assertEquals("Япония", AnkiTemplate.render("{{back}}", ctx, true))
    }
}
