package app.wanki.anki

import app.wanki.data.NoteEntity
import app.wanki.data.NoteTypeEntity
import org.json.JSONArray
import org.json.JSONObject

enum class NoteKind { STANDARD, CLOZE }

data class CardTemplate(val name: String, val question: String, val answer: String)

/** Field names and card templates; [BASIC] is built in, others come from Anki. */
data class NoteType(
    val id: String,
    val name: String,
    val fields: List<String>,
    val templates: List<CardTemplate>,
    val kind: NoteKind = NoteKind.STANDARD,
    val css: String = "",
) {
    val isBasic: Boolean get() = id == BASIC.id

    /**
     * The field written by hand for template [ord]: the first field the answer adds to the
     * question (Basic → Back, reversed → Front); for cloze, the field holding the deletions.
     */
    fun writeFieldOrd(ord: Int): Int {
        val t = templates.getOrNull(if (kind == NoteKind.CLOZE) 0 else ord) ?: return if (fields.size > 1) 1 else 0
        val q = AnkiTemplate.fieldsUsed(t.question)
        val a = AnkiTemplate.fieldsUsed(t.answer)
        val name = if (kind == NoteKind.CLOZE) q.firstOrNull { f -> Regex("""\{\{[^\}]*cloze:\s*${Regex.escape(f)}\s*\}\}""").containsMatchIn(t.question) } ?: q.firstOrNull()
        else fields.firstOrNull { it in a && it !in q }
        val i = name?.let { n -> fields.indexOfFirst { it.equals(n, ignoreCase = true) } } ?: -1
        return if (i >= 0) i else if (fields.size > 1) 1 else 0
    }

    fun toEntity(updatedAt: Long = System.currentTimeMillis()) = NoteTypeEntity(
        id = id,
        name = name,
        fields = JSONArray(fields).toString(),
        templates = JSONArray(templates.map { JSONObject().put("name", it.name).put("q", it.question).put("a", it.answer) }).toString(),
        kind = kind.name,
        css = css,
        updatedAt = updatedAt,
    )

    companion object {
        val BASIC = NoteType(
            id = "basic",
            name = "Вопрос и ответ",
            fields = listOf("Вопрос", "Ответ"),
            templates = listOf(CardTemplate("Карточка", "{{Вопрос}}", "{{Ответ}}")),
        )

        fun from(e: NoteTypeEntity): NoteType {
            val f = JSONArray(e.fields)
            val t = JSONArray(e.templates)
            return NoteType(
                id = e.id,
                name = e.name,
                fields = (0 until f.length()).map { f.getString(it) },
                templates = (0 until t.length()).map { i ->
                    val o = t.getJSONObject(i)
                    CardTemplate(o.optString("name"), o.optString("q"), o.optString("a"))
                },
                kind = runCatching { NoteKind.valueOf(e.kind) }.getOrDefault(NoteKind.STANDARD),
                css = e.css,
            )
        }
    }
}

/** What one card shows and what is written for it. */
data class CardContent(
    val question: List<CardBlock>,
    /** The answer side without the repeated question. */
    val answer: List<CardBlock>,
    /** Plain text written by hand (and checked); also what is read aloud. */
    val writeAnswer: String,
)

object CardRenderer {
    /**
     * Basic notes: the question is the field that is not written; Anki notes: the card's
     * templates. [deckPath] and [tags] feed {{Deck}} / {{Tags}}.
     */
    fun render(type: NoteType, note: NoteEntity, ord: Int, writeFieldOrd: Int, deckPath: String = ""): CardContent {
        val values = note.fields.split(NoteEntity.FIELD_SEPARATOR)
        if (type.isBasic) {
            val answer = values.getOrElse(writeFieldOrd) { "" }
            val question = values.getOrElse(if (writeFieldOrd == 0) 1 else 0) { "" }
            return CardContent(CardHtml.withMasks(question, false), CardHtml.withMasks(answer, true), CardHtml.withoutMasks(answer))
        }
        val fields = type.fields.mapIndexed { i, name -> name to values.getOrElse(i) { "" } }.toMap()
        val template = type.templates.getOrNull(if (type.kind == NoteKind.CLOZE) 0 else ord) ?: type.templates.firstOrNull()
            ?: CardTemplate("", "", "")
        val ctx = AnkiTemplate.Context(fields, note.tags, deckPath, type.name, template.name, ord)
        val q = AnkiTemplate.render(template.question, ctx, question = true)
        val a = AnkiTemplate.render(template.answer, ctx, question = false, frontSide = q)
        val source = values.getOrElse(writeFieldOrd) { "" }
        val write = if (type.kind == NoteKind.CLOZE) {
            AnkiTemplate.clozeAnswers(source, ord + 1).joinToString(" ") { CardHtml.plain(it) }
        } else {
            CardHtml.plain(source)
        }
        return CardContent(CardHtml.blocks(q), CardHtml.blocks(CardHtml.answerOnly(a, q)), write)
    }
}
