package app.wanki.anki

import app.wanki.importer.AnkiText

/** What a rendered card shows: text paragraphs, images and sounds from the media folder. */
sealed interface CardBlock {
    data class Text(val text: String) : CardBlock
    data class Image(val file: String) : CardBlock
    data class Sound(val file: String) : CardBlock
    /** A tape mask from a page, by its source ref id (stable when the mask moves); open on the answer side. */
    data class Mask(val refId: String, val revealed: Boolean) : CardBlock
}

/** Turns rendered card HTML into [CardBlock]s; styles, scripts and markup are dropped. */
object CardHtml {
    private val styleOrScript = Regex("""(?is)<(style|script)\b.*?</\1\s*>""")
    private val media = Regex("""(?is)<img\b[^>]*?\bsrc\s*=\s*["']?([^"' >]+)["']?[^>]*>|\[sound:([^\]]+)\]""")
    private val rt = Regex("""(?is)<rt>(.*?)</rt>""")
    private val rp = Regex("""(?is)<rp>.*?</rp>""")
    private val maskToken = Regex("""\[mask:([^\]\s]+)\]""")
    private val answerRule = Regex("""(?is)<hr\b[^>]*\bid\s*=\s*["']?answer["']?[^>]*>""")

    /** The note field token of a mask card. */
    fun maskToken(refId: String) = "[mask:$refId]"

    /** Plain text with `[mask:]` tokens split out as [CardBlock.Mask] (basic notes are not HTML). */
    fun withMasks(text: String, revealed: Boolean): List<CardBlock> {
        if ("[mask:" !in text) return listOf(CardBlock.Text(text))
        val out = ArrayList<CardBlock>()
        var last = 0
        for (m in maskToken.findAll(text)) {
            text.substring(last, m.range.first).trim().takeIf { it.isNotEmpty() }?.let { out += CardBlock.Text(it) }
            out += CardBlock.Mask(m.groupValues[1], revealed)
            last = m.range.last + 1
        }
        text.substring(last).trim().takeIf { it.isNotEmpty() }?.let { out += CardBlock.Text(it) }
        return out
    }

    /** [text] without its mask tokens. */
    fun withoutMasks(text: String) = maskToken.replace(text, "").trim()

    fun blocks(html: String): List<CardBlock> {
        val clean = rt.replace(rp.replace(styleOrScript.replace(html, ""), ""), "($1)")
        val out = ArrayList<CardBlock>()
        var last = 0
        fun text(upTo: Int) {
            val t = AnkiText.clean(clean.substring(last, upTo))
            if (t.isNotBlank()) out += CardBlock.Text(t)
        }
        for (m in media.findAll(clean)) {
            text(m.range.first)
            val img = m.groupValues[1]
            val sound = m.groupValues[2]
            if (img.isNotEmpty()) out += CardBlock.Image(decode(img)) else out += CardBlock.Sound(sound.trim())
            last = m.range.last + 1
        }
        text(clean.length)
        return out
    }

    /** The answer side without the repeated question: after <hr id=answer>, or minus the FrontSide prefix. */
    fun answerOnly(answerHtml: String, questionHtml: String): String {
        answerRule.find(answerHtml)?.let { return answerHtml.substring(it.range.last + 1) }
        val q = questionHtml.trim()
        val a = answerHtml.trim()
        return if (q.isNotEmpty() && a.startsWith(q)) a.removePrefix(q) else answerHtml
    }

    /** Plain text of a field, as written by hand: markup, sounds and furigana readings removed. */
    fun plain(html: String): String {
        val noSound = Regex("""\[sound:[^\]]*\]""").replace(styleOrScript.replace(html, ""), "")
        val noReadings = Regex(""" ?([^ >\[]+?)\[(.+?)\]""").replace(noSound) { it.groupValues[1] }
        return AnkiText.clean(noReadings).trim()
    }

    /** Percent-escapes only ('+' is a literal plus in file names). */
    private fun decode(src: String): String =
        if ('%' !in src) src else runCatching { java.net.URLDecoder.decode(src.replace("+", "%2B"), "UTF-8") }.getOrDefault(src)
}
