package app.wanki.anki

/**
 * Anki's card template language, rendered to HTML: {{Field}}, {{FrontSide}}, sections
 * {{#Field}}…{{/Field}} and {{^Field}}…{{/Field}}, special fields (Tags, Deck, Subdeck, Type,
 * Card), filters (text, kana, kanji, furigana, hint, type, cloze, tts — applied right to left) and
 * cloze deletions {{c1::answer::hint}}.
 */
object AnkiTemplate {
    data class Context(
        val fields: Map<String, String>,
        val tags: String = "",
        val deck: String = "",
        val noteType: String = "",
        val card: String = "",
        /** Card ordinal: cloze card n shows deletion c(n+1). */
        val ord: Int = 0,
    )

    private val tag = Regex("""\{\{(.*?)\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val cloze = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val furigana = Regex(""" ?([^ >\[]+?)\[(.+?)\]""")
    private val htmlTag = Regex("""<[^>]*>""")

    fun render(template: String, ctx: Context, question: Boolean, frontSide: String = ""): String {
        val tokens = tokenize(template)
        val (nodes, _) = parse(tokens, 0, null)
        return eval(nodes, ctx, question, frontSide)
    }

    /** Field names referenced anywhere in a template (filters stripped). */
    fun fieldsUsed(template: String): Set<String> =
        tag.findAll(template).map { it.groupValues[1].trim().removePrefix("#").removePrefix("^").removePrefix("/") }
            .map { it.substringAfterLast(':').trim() }
            .filter { it.isNotEmpty() && it != "FrontSide" }
            .toSet()

    /** Plain answers of cloze deletion number [n] (1-based) in [text]. */
    fun clozeAnswers(text: String, n: Int): List<String> =
        cloze.findAll(text).filter { it.groupValues[1].toIntOrNull() == n }.map { it.groupValues[2] }.toList()

    // --- parsing --------------------------------------------------------------------------------

    private sealed interface Token {
        data class Text(val text: String) : Token
        data class Tag(val body: String) : Token
    }

    private sealed interface Node {
        data class Text(val text: String) : Node
        data class Field(val filters: List<String>, val name: String) : Node
        data class Section(val name: String, val inverted: Boolean, val children: List<Node>) : Node
    }

    private fun tokenize(t: String): List<Token> {
        val out = ArrayList<Token>()
        var last = 0
        for (m in tag.findAll(t)) {
            if (m.range.first > last) out += Token.Text(t.substring(last, m.range.first))
            out += Token.Tag(m.groupValues[1].trim())
            last = m.range.last + 1
        }
        if (last < t.length) out += Token.Text(t.substring(last))
        return out
    }

    /** Parses until the closing tag of [section]; returns the nodes and the next token index. */
    private fun parse(tokens: List<Token>, start: Int, section: String?): Pair<List<Node>, Int> {
        val out = ArrayList<Node>()
        var i = start
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                is Token.Text -> out += Node.Text(t.text)
                is Token.Tag -> {
                    val body = t.body
                    when {
                        body.startsWith("/") -> {
                            if (section != null && body.removePrefix("/").trim() == section) return out to i + 1
                        }
                        body.startsWith("#") || body.startsWith("^") -> {
                            val name = body.substring(1).trim()
                            val (children, next) = parse(tokens, i + 1, name)
                            out += Node.Section(name, body.startsWith("^"), children)
                            i = next
                            continue
                        }
                        else -> {
                            val parts = body.split(':').map { it.trim() }
                            out += Node.Field(parts.dropLast(1), parts.last())
                        }
                    }
                }
            }
            i++
        }
        return out to i
    }

    // --- evaluation -----------------------------------------------------------------------------

    private fun eval(nodes: List<Node>, ctx: Context, question: Boolean, frontSide: String): String = buildString {
        for (n in nodes) when (n) {
            is Node.Text -> append(n.text)
            is Node.Section -> {
                val present = value(n.name, ctx, frontSide)?.let { htmlTag.replace(it, "").isNotBlank() } == true
                if (present != n.inverted) append(eval(n.children, ctx, question, frontSide))
            }
            is Node.Field -> append(field(n, ctx, question, frontSide))
        }
    }

    private fun value(name: String, ctx: Context, frontSide: String): String? = when (name) {
        "FrontSide" -> frontSide
        "Tags" -> ctx.tags
        "Deck" -> ctx.deck
        "Subdeck" -> ctx.deck.substringAfterLast("::")
        "Type" -> ctx.noteType
        "Card" -> ctx.card
        "CardFlag" -> ""
        else -> ctx.fields[name] ?: ctx.fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun field(n: Node.Field, ctx: Context, question: Boolean, frontSide: String): String {
        var v = value(n.name, ctx, frontSide) ?: return ""
        for (f in n.filters.asReversed()) {
            val name = f.substringBefore(' ')
            v = when {
                name == "text" -> htmlTag.replace(v, "")
                name == "kana" -> furigana.replace(v) { m -> m.groupValues[2] }
                name == "kanji" -> furigana.replace(v) { m -> m.groupValues[1] }
                name == "furigana" -> furigana.replace(v) { m -> "<ruby>${m.groupValues[1]}<rt>${m.groupValues[2]}</rt></ruby>" }
                name == "cloze" -> renderCloze(v, ctx.ord + 1, question)
                // typing the answer is replaced by handwriting; the answer side shows the field
                name == "type" -> if (question) "" else v
                name == "hint" -> v
                name == "tts" || name.startsWith("tts") -> ""
                else -> v
            }
        }
        return v
    }

    private fun renderCloze(text: String, n: Int, question: Boolean): String = cloze.replace(text) { m ->
        val num = m.groupValues[1].toIntOrNull()
        val answer = m.groupValues[2]
        val hint = m.groupValues.getOrNull(3).orEmpty()
        when {
            num != n -> answer
            question -> "<span class=\"cloze\">[" + hint.ifEmpty { "…" } + "]</span>"
            else -> "<span class=\"cloze\">$answer</span>"
        }
    }
}
