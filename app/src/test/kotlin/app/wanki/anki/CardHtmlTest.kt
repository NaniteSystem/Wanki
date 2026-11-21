package app.wanki.anki

import kotlin.test.Test
import kotlin.test.assertEquals

class CardHtmlTest {
    @Test
    fun textImagesAndSoundsInOrder() {
        val blocks = CardHtml.blocks("<style>.card{}</style><div>犬</div><img src=\"dog%20photo.jpg\">[sound:inu.mp3]<br>собака")
        assertEquals(
            listOf(CardBlock.Text("犬"), CardBlock.Image("dog photo.jpg"), CardBlock.Sound("inu.mp3"), CardBlock.Text("собака")),
            blocks,
        )
    }

    @Test
    fun rubyBecomesReadingInParentheses() {
        assertEquals(listOf(CardBlock.Text("日本(にほん)")), CardHtml.blocks("<ruby>日本<rp>(</rp><rt>にほん</rt><rp>)</rp></ruby>"))
    }

    @Test
    fun plusInFileNamesIsKept() {
        assertEquals(listOf(CardBlock.Image("c++.png")), CardHtml.blocks("<img src='c++.png'>"))
    }

    @Test
    fun answerWithoutTheQuestion() {
        assertEquals("Япония", CardHtml.answerOnly("日本<hr id=answer>Япония", "日本"))
        assertEquals("<br>Япония", CardHtml.answerOnly("日本<br>Япония", "日本"))
    }

    @Test
    fun plainFieldForWriting() {
        assertEquals("日本語", CardHtml.plain("<b>日本語[にほんご]</b>[sound:a.mp3]"))
    }

    @Test
    fun maskTokenBecomesBlock() {
        val token = CardHtml.maskToken("r1")
        assertEquals(listOf(CardBlock.Mask("r1", false)), CardHtml.withMasks(token, revealed = false))
        assertEquals(listOf(CardBlock.Mask("r1", true), CardBlock.Text("東京")), CardHtml.withMasks("$token\n東京", revealed = true))
        assertEquals(listOf(CardBlock.Text("no mask")), CardHtml.withMasks("no mask", revealed = true))
        assertEquals("東京", CardHtml.withoutMasks("$token\n東京"))
    }
}
