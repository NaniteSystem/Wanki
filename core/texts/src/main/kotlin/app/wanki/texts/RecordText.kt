package app.wanki.texts

/**
 * One piece of a record's text: a typed text block or recognised handwriting. On a page [top] and [left] place it (world
 * units) so the parts can be read top to bottom; in a note they are left at 0 and the order of the blocks is kept.
 */
data class RecordPart(val text: String, val top: Float = 0f, val left: Float = 0f)

/** What a note or a diary day gives for «Учить наизусть». */
sealed interface RecordText {
    /** A new text to open in the editor: [content] as written, [kind] a guess the user can change there. */
    data class Ready(val title: String, val content: String, val kind: TextKind) : RecordText

    /** Nothing written at all. */
    data object Empty : RecordText

    /** Only handwriting that has no recognised text (yet). */
    data object Unrecognized : RecordText
}

/**
 * «Учить наизусть» from a note or a diary day: the record's text is copied once into a new text — no link stays, so
 * editing the record later changes nothing in the text (like a one-off card from a selection).
 */
object RecordTexts {
    private val hasContent = Regex("[\\p{L}\\p{N}]")
    private const val POEM_LINE = 60
    private const val POEM_LONGEST = 90

    /**
     * The record's text: [parts] in reading order (by [RecordPart.top], then [RecordPart.left]; equal places keep their
     * order), each trimmed of blank edge lines and trailing spaces, empty ones dropped, one per line. [title] is the
     * record's own title; blank — the editor names the text by its first piece. [hasInk] tells a record with unread
     * handwriting from an empty one.
     */
    fun build(title: String, parts: List<RecordPart>, hasInk: Boolean): RecordText {
        val content = join(parts)
        if (!hasContent.containsMatchIn(content)) return if (hasInk) RecordText.Unrecognized else RecordText.Empty
        return RecordText.Ready(title.trim(), content, kind(content))
    }

    fun join(parts: List<RecordPart>): String = parts.withIndex()
        .sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.index }))
        .map { clean(it.value.text) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    /**
     * A guess: a poem when there are several lines and all are short (verse, a list of sayings), else prose (typed
     * prose runs in long paragraphs). The user settles it in the editor's «Как делить».
     */
    fun kind(content: String): TextKind {
        val lines = content.lines().map { it.trim() }.filter { hasContent.containsMatchIn(it) }
        if (lines.size < 2) return TextKind.PROSE
        val average = lines.sumOf { it.length } / lines.size
        return if (average <= POEM_LINE && lines.all { it.length <= POEM_LONGEST }) TextKind.POEM else TextKind.PROSE
    }

    /** Trailing spaces off every line, blank lines off both ends; the inner text stays exactly as written. */
    private fun clean(text: String): String =
        text.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
}
