package app.wanki.texts

/** How a text is cut into pieces: a poem by lines, prose by sentences. */
enum class TextKind { POEM, PROSE }

/** Which scheduler plans the text's pieces. */
enum class TextSchedule { FSRS, HIFZ }

/** Cuts a text into the pieces that are learned (and recalled) one at a time. */
object PieceSplitter {
    private const val SPACED_TERMINATORS = ".!?…؟"
    private const val CJK_TERMINATORS = "。！？"
    private const val CLOSERS = "»\"”’)]」』"
    private val hasContent = Regex("[\\p{L}\\p{N}]")

    /** Trimmed pieces in order; pieces without a letter or digit (`* * *`, `—`) are dropped. */
    fun split(text: String, kind: TextKind): List<String> {
        val lines = text.lines()
        val raw = when (kind) {
            TextKind.POEM -> lines
            TextKind.PROSE -> lines.flatMap { sentences(it) }
        }
        return raw.map { it.trim() }.filter { hasContent.containsMatchIn(it) }
    }

    /** Breaks a line after a terminator (plus closing quotes) unless a lowercase letter follows. */
    private fun sentences(line: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val cjk = c in CJK_TERMINATORS
            if (c in SPACED_TERMINATORS || cjk) {
                var end = i + 1
                while (end < line.length && (line[end] in SPACED_TERMINATORS || line[end] in CJK_TERMINATORS)) end++
                while (end < line.length && line[end] in CLOSERS) end++
                val atEnd = end >= line.length
                val next = line.drop(end).firstOrNull { !it.isWhitespace() }
                val spaced = !atEnd && line[end].isWhitespace()
                if (atEnd || cjk || (spaced && next?.isLowerCase() != true)) {
                    out += line.substring(start, end)
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        if (start < line.length) out += line.substring(start)
        return out
    }
}
