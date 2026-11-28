package app.wanki.ui.texts

import app.wanki.data.NoteBlockType
import app.wanki.data.PageObjectKind
import app.wanki.data.Repository
import app.wanki.texts.RecordPart
import app.wanki.texts.RecordText
import app.wanki.texts.RecordTexts

/** Where «Учить наизусть» takes its text from. */
enum class RecordKind { NOTE, DIARY }

/**
 * A note ([RecordKind.NOTE], [id] = note id) or a diary day ([RecordKind.DIARY], [id] = page id) copied once into a new
 * text: the text keeps no link to it.
 */
data class RecordRef(val kind: RecordKind, val id: String)

/**
 * The stored text of [ref]: a note's text blocks and recognised handwriting blocks in order; a diary page's text blocks
 * and recognised lines (corrections included) top to bottom. [hasInk] — the caller sees handwriting on the record, so a
 * record without text is [RecordText.Unrecognized] rather than [RecordText.Empty].
 */
suspend fun recordText(repository: Repository, ref: RecordRef, hasInk: Boolean = false): RecordText = when (ref.kind) {
    RecordKind.NOTE -> {
        val note = repository.textNote(ref.id)
        val blocks = repository.noteBlocks(ref.id).sortedBy { it.blockIndex }
        val parts = if (blocks.isEmpty()) {
            // notes from before blocks existed keep their text in the body
            listOf(RecordPart(note?.body.orEmpty()))
        } else blocks.map { b ->
            RecordPart(if (b.type == NoteBlockType.INK.name) b.recognizedText else b.text)
        }
        RecordTexts.build(note?.title.orEmpty(), parts, hasInk)
    }
    RecordKind.DIARY -> {
        val lines = repository.pageLines(ref.id)
        val ink = if (lines.isEmpty()) {
            // a page recognised before lines existed has only the joined text
            listOf(RecordPart(repository.recognizedText(ref.id)))
        } else lines.map { RecordPart(it.shown, it.top, it.left) }
        val blocks = repository.pageObjects(ref.id)
            .filter { it.kind == PageObjectKind.TEXT.name }
            .map { RecordPart(it.text, it.y, it.x) }
        // a diary day is named by its first piece, like a text typed in
        RecordTexts.build("", ink + blocks, hasInk)
    }
}

/** What to tell the user when [r] gives nothing to learn; null when it does. */
fun recordTextProblem(r: RecordText): String? = when (r) {
    is RecordText.Ready -> null
    RecordText.Empty -> "В записи нет текста — учить нечего"
    RecordText.Unrecognized -> "Рукописный текст ещё не распознан — нечего учить. Подождите немного или проверьте распознавание"
}
