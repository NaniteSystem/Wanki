package app.wanki.importer

/** Turns an Anki field (HTML with Anki markup) into plain text for our cards. */
object AnkiText {
    private val sound = Regex("""\[sound:[^\]]*]""")
    private val lineBreaks = Regex("""(?i)<\s*(br|/div|/p|/li)\s*/?>""")
    private val tags = Regex("""<[^>]+>""")
    private val numericEntity = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val spaces = Regex("""[ \t ]+""")
    private val blankLines = Regex("""\n\s*\n+""")
    private val named = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'")

    fun clean(html: String): String {
        var s = sound.replace(html, "")
        s = lineBreaks.replace(s, "\n")
        s = tags.replace(s, "")
        s = numericEntity.replace(s) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            cp?.let { String(Character.toChars(it)) } ?: m.value
        }
        for ((k, v) in named) s = s.replace(k, v)
        s = spaces.replace(s, " ")
        s = blankLines.replace(s, "\n")
        return s.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** Anki deck names use "::" (legacy) or 0x1F (new schema) between levels. */
    fun deckName(raw: String): String = raw.replace('\u001F', ':').replace(":", "::").replace("::::", "::")

    /** Rough language guess from answers, to pick the recognizer and references. */
    fun guessLanguage(samples: List<String>): String {
        val text = samples.joinToString("")
        fun count(p: (Int) -> Boolean) = text.codePoints().filter(p).count()
        val kana = count { it in 0x3040..0x30FF }
        val han = count { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        val hangul = count { Character.UnicodeScript.of(it) == Character.UnicodeScript.HANGUL }
        val arabic = count { Character.UnicodeScript.of(it) == Character.UnicodeScript.ARABIC }
        val cyr = count { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }
        val latin = count { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
        val best = listOf("ja" to kana * 3 + han, "zh" to if (kana == 0L) han * 2 else 0, "ko" to hangul * 2, "ar" to arabic * 2, "ru" to cyr, "en" to latin)
            .maxBy { it.second }
        return if (best.second == 0L) "en" else best.first
    }
}
