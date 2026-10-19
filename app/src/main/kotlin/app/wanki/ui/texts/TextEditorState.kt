package app.wanki.ui.texts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.wanki.data.EditedPiece
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import app.wanki.texts.Words

/** One piece in the editor; [id] null = a new piece that copies the schedule of [scheduleFrom] (split or join). */
internal data class Draft(
    val key: Long, val id: String?, val value: TextFieldValue, val note: String = "", val scheduleFrom: String? = null,
    val noteOpen: Boolean = note.isNotEmpty(),
)

/**
 * The text editor: settings, the whole-text field and the pieces. Typing in the whole-text field re-splits it and
 * aligns the result with the pieces so edited ones keep their ids; a piece with an id that drops out on the way (a
 * line merged into its neighbour, deleted letter by letter, a sentence end deleted) waits in [dropped] and comes back
 * with its id, schedule and note when its exact text does. Editing, splitting or joining a piece rewrites the
 * whole-text field as one piece per line.
 */
@Stable
internal class TextEditorState {
    var title by mutableStateOf("")
    var language by mutableStateOf("ru")
        private set
    /** Until the user picks a language it follows the detected one. */
    var languagePicked by mutableStateOf(false)
        private set
    /** [language] came from Language ID, not the default. */
    var languageDetected by mutableStateOf(false)
        private set
    var kind by mutableStateOf(TextKind.POEM)
        private set
    var schedule by mutableStateOf(TextSchedule.FSRS)
    var portion by mutableIntStateOf(defaultPortion(TextKind.POEM))
        private set
    private var portionTouched = false
    var content by mutableStateOf("")
        private set
    val drafts = mutableStateListOf<Draft>()
    var changed by mutableStateOf(false)
        private set
    private var nextKey = 0L
    private val dropped = ArrayList<Draft>()
    /** The stored content of each loaded piece: a dropped piece also comes back when this text does. */
    private val saved = HashMap<String, String>()

    val canSave: Boolean get() = drafts.any { PieceDrafts.hasContent(it.value.text) }

    fun load(t: TextEntity, pieces: List<TextPieceEntity>) {
        title = t.title
        language = t.language
        languagePicked = true
        kind = kindOf(t.kind)
        schedule = scheduleOf(t.schedule)
        portion = t.portionSize
        portionTouched = true
        drafts.clear()
        drafts += pieces.map { Draft(nextKey++, it.id, TextFieldValue(it.content), it.note) }
        content = pieces.joinToString("\n") { it.content }
        dropped.clear()
        saved.clear()
        pieces.associateTo(saved) { it.id to it.content }
    }

    fun detected(code: String) { if (!languagePicked) { language = code; languageDetected = true } }

    fun pickLanguage(code: String) { language = code; languagePicked = true; changed = true }

    fun changeSchedule(s: TextSchedule) { schedule = s; changed = true }

    fun changePortion(n: Int) { portion = n.coerceIn(1, 20); portionTouched = true; changed = true }

    fun changeKind(k: TextKind) {
        if (k == kind) return
        kind = k
        if (!portionTouched) portion = defaultPortion(k)
        changed = true
        resplit(PieceDrafts.split(content, k))
    }

    fun editContent(text: String) {
        if (text == content) return
        content = text
        changed = true
        resplit(PieceDrafts.split(text, kind, drafts.mapTo(HashSet()) { it.value.text.trim() }))
    }

    /** A newline typed or pasted into a piece cuts it there. */
    fun editPiece(i: Int, v: TextFieldValue) {
        val d = drafts[i]
        if ('\n' in v.text) {
            val parts = v.text.split('\n').map { it.trim() }.filter(PieceDrafts::hasContent)
            if (parts.isEmpty()) {
                drafts[i] = d.copy(value = TextFieldValue(""))
            } else {
                drafts[i] = d.copy(value = TextFieldValue(parts[0], TextRange(parts[0].length)))
                parts.drop(1).forEachIndexed { k, part -> drafts.add(i + 1 + k, cutOff(part, d)) }
            }
        } else {
            if (v.text == d.value.text) { drafts[i] = d.copy(value = v); return } // cursor moved
            drafts[i] = d.copy(value = v)
        }
        piecesChanged()
    }

    fun editNote(i: Int, note: String) { drafts[i] = drafts[i].copy(note = note); changed = true }

    fun openNote(i: Int) { drafts[i] = drafts[i].copy(noteOpen = true) }

    /** The cursor stands inside the piece with text on both sides. */
    fun canSplit(i: Int): Boolean {
        val v = drafts[i].value
        if (!v.selection.collapsed) return false
        val at = PieceDrafts.cutPoint(v.text, v.selection.start, Words.needsDictionary(language))
        return PieceDrafts.hasContent(v.text.take(at)) && PieceDrafts.hasContent(v.text.drop(at))
    }

    fun split(i: Int) {
        if (!canSplit(i)) return
        val d = drafts[i]
        val at = PieceDrafts.cutPoint(d.value.text, d.value.selection.start, Words.needsDictionary(language))
        val head = d.value.text.take(at).trimEnd()
        drafts[i] = d.copy(value = TextFieldValue(head, TextRange(head.length)))
        drafts.add(i + 1, cutOff(d.value.text.drop(at).trimStart(), d))
        piecesChanged()
    }

    /** The merged piece keeps the previous piece's schedule; notes are joined. */
    fun joinWithPrevious(i: Int) {
        if (i <= 0) return
        val p = drafts[i - 1]
        val c = drafts[i]
        val head = p.value.text.trim()
        val sep = if (Words.needsDictionary(language)) "" else " "
        drafts[i - 1] = p.copy(
            id = p.id ?: c.id, scheduleFrom = p.scheduleFrom ?: c.scheduleFrom,
            value = TextFieldValue(head + sep + c.value.text.trim(), TextRange(head.length)),
            note = listOf(p.note.trim(), c.note.trim()).filter { it.isNotEmpty() }.joinToString(" "),
            noteOpen = p.noteOpen || c.noteOpen,
        )
        drafts.removeAt(i)
        if (c.id != null && c.id != drafts[i - 1].id) dropped += c
        piecesChanged()
    }

    /** The title, or the first piece when it is blank. */
    fun resolvedTitle(): String {
        title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val first = drafts.firstOrNull { PieceDrafts.hasContent(it.value.text) }?.value?.text?.trim() ?: return ""
        if (first.length <= TITLE_LENGTH) return first
        val cut = first.take(TITLE_LENGTH)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > TITLE_LENGTH / 2 } ?: cut.length).trimEnd(' ', ',', ';', ':', '—', '-') + "…"
    }

    fun pieces(): List<EditedPiece> = drafts.filter { PieceDrafts.hasContent(it.value.text) }
        .map { EditedPiece(it.id, it.value.text.trim(), it.note.trim(), it.scheduleFrom) }

    /** A change made to the pieces themselves: the whole-text field follows. */
    private fun piecesChanged() {
        changed = true
        content = drafts.joinToString("\n") { it.value.text.trim() }
    }

    /** A dropped piece whose exact text (as dropped or as stored) is [text], taken out of [dropped]. */
    private fun takeDropped(text: String): Draft? {
        val i = dropped.indexOfLast { it.value.text.trim() == text || saved[it.id] == text }
        return if (i < 0) null else dropped.removeAt(i).copy(value = TextFieldValue(text))
    }

    /** The piece cut off [from]: a dropped piece with this text comes back, else a new one copying [from]'s schedule. */
    private fun cutOff(text: String, from: Draft): Draft =
        takeDropped(text) ?: Draft(nextKey++, null, TextFieldValue(text), scheduleFrom = from.id ?: from.scheduleFrom)

    /**
     * The whole-text field split into [pieces], aligned with the drafts of a keystroke ago. A piece paired with a
     * draft that has an id keeps it; any other piece first takes back a dropped piece with exactly its text, then
     * keeps the id-less draft it was paired with, else is new (copying the schedule of the piece it was split from).
     */
    private fun resplit(pieces: List<String>) {
        val old = drafts.toList()
        val origins = PieceDrafts.align(old.map { it.value.text.trim() }, pieces)
        val next = pieces.mapIndexed { j, s ->
            val o = origins[j]
            val prev = o.old?.let(old::get)
            val d = prev?.takeIf { it.id != null }
                ?: takeDropped(s)
                ?: prev
                ?: Draft(nextKey++, null, TextFieldValue(s), scheduleFrom = o.splitFrom?.let(old::get)?.let { it.id ?: it.scheduleFrom })
            if (d.value.text == s) d else d.copy(value = TextFieldValue(s))
        }
        val kept = next.mapTo(HashSet()) { it.key }
        dropped += old.filter { it.id != null && it.key !in kept }
        if (next != old) { drafts.clear(); drafts.addAll(next) }
    }

    companion object {
        private const val TITLE_LENGTH = 60
        fun defaultPortion(kind: TextKind) = if (kind == TextKind.POEM) 4 else 3
    }
}
