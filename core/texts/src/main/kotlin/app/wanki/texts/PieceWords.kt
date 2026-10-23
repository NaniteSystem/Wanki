package app.wanki.texts

/** A word of a piece as `[start, end)` offsets, what must be typed for it, and other spellings accepted instead. */
data class PieceWord(val start: Int, val end: Int, val reading: String, val forms: List<String>) {
    fun unit(): TypingUnit = TypingUnit(reading, forms)
}

/** A token found by a morphological analyser; [reading] is null when it has none (punctuation, unknown words). */
data class MorphToken(val start: Int, val end: Int, val reading: String?)

fun interface Morphology {
    fun tokens(text: String): List<MorphToken>
}

object PieceWords {
    private const val ENTRY = '\u001E'
    private const val FIELD = '\u001F'
    private const val FORM = '\u001D'

    fun build(
        content: String,
        language: String,
        breaker: WordBreaker,
        morphology: Morphology?,
        overrides: Map<String, ReadingOverride>,
    ): List<PieceWord> {
        val out = ArrayList<PieceWord>()
        if (Words.base(language) != "ja") {
            for (w in breaker.words(content)) {
                val surface = content.substring(w.start, w.end)
                make(w.start, w.end, surface, null, overrides[surface], false)?.let { out += it }
            }
            return out
        }
        val surfaces = overrides.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        var gapStart = 0
        var i = 0
        while (i < content.length) {
            val hit = surfaces.firstOrNull { content.startsWith(it, i) }
            if (hit == null) { i++; continue }
            gap(content, gapStart, i, breaker, morphology, overrides, out)
            make(i, i + hit.length, hit, null, overrides[hit], true)?.let { out += it }
            i += hit.length
            gapStart = i
        }
        gap(content, gapStart, content.length, breaker, morphology, overrides, out)
        return out
    }

    private fun gap(
        content: String, from: Int, to: Int, breaker: WordBreaker, morphology: Morphology?,
        overrides: Map<String, ReadingOverride>, out: MutableList<PieceWord>,
    ) {
        if (from >= to) return
        val text = content.substring(from, to)
        val tokens = (morphology?.tokens(text) ?: breaker.words(text).map { MorphToken(it.start, it.end, null) })
            .filter { it.start >= 0 && it.end <= text.length && it.start < it.end }
        var k = 0
        while (k < tokens.size) {
            val run = numberRun(text, tokens, k)
            if (run != null) {
                val (next, word) = run
                val start = tokens[k].start
                val end = tokens[next - 1].end
                val surface = text.substring(start, end)
                make(from + start, from + end, surface, word.reading, overrides[surface], true, word.forms)?.let { out += it }
                k = next
                continue
            }
            val t = tokens[k]
            val surface = text.substring(t.start, t.end)
            make(from + t.start, from + t.end, surface, t.reading, overrides[surface], true)?.let { out += it }
            k++
        }
    }

    /**
     * Adjacent number tokens from [k] (the analyser splits 二十三 into 二|十|三) and a counter right after them
     * (3|月) as one word: the index after the run and the word; null when [k] does not start a number.
     */
    private fun numberRun(text: String, tokens: List<MorphToken>, k: Int): Pair<Int, NumberWord>? {
        fun surface(i: Int) = text.substring(tokens[i].start, tokens[i].end)
        fun adjacent(i: Int) = i < tokens.size && tokens[i].start == tokens[i - 1].end
        if (!JapaneseNumbers.isNumber(surface(k))) return null
        var e = k + 1
        while (adjacent(e) && JapaneseNumbers.isNumber(surface(e))) e++
        val number = text.substring(tokens[k].start, tokens[e - 1].end)
        val counter = if (adjacent(e) && JapaneseNumbers.isCounter(surface(e))) surface(e) else ""
        val word = JapaneseNumbers.word(number, counter) ?: return null
        return (if (counter.isEmpty()) e else e + 1) to word
    }

    private fun make(
        start: Int, end: Int, surface: String, tokenReading: String?, override: ReadingOverride?, japanese: Boolean,
        extra: List<String> = emptyList(),
    ): PieceWord? {
        if (surface.codePoints().noneMatch { Character.isLetterOrDigit(it) }) return null
        val reading = override?.reading ?: tokenReading?.takeIf { it.isNotEmpty() }?.let(::hiragana) ?: surface
        val forms = LinkedHashSet<String>()
        if (!sameFold(surface, reading)) forms += surface
        override?.forms?.let { forms += it }
        if (japanese) {
            forms += JapaneseNumbers.forms(surface)
            if (override == null) {
                forms += extra
                forms += JapaneseReadings.alternatives(surface)
            }
        }
        return PieceWord(start, end, reading, forms.toList())
    }

    private fun sameFold(a: String, b: String) = TypingText.fold(a).chars.contentEquals(TypingText.fold(b).chars)

    private fun hiragana(s: String): String =
        String(CharArray(s.length) { val c = s[it]; if (c in 'ァ'..'ヶ') c - 0x60 else c })

    fun encode(words: List<PieceWord>): String = words.joinToString(ENTRY.toString()) {
        listOf(it.start.toString(), it.end.toString(), esc(it.reading), it.forms.joinToString(FORM.toString(), transform = ::esc))
            .joinToString(FIELD.toString())
    }

    fun decode(s: String): List<PieceWord> {
        if (s.isEmpty()) return emptyList()
        return s.split(ENTRY).map { e ->
            val f = e.split(FIELD)
            PieceWord(f[0].toInt(), f[1].toInt(), unesc(f[2]), forms(f[3]))
        }
    }

    fun encodeReadings(m: Map<String, ReadingOverride>): String = m.entries.joinToString(ENTRY.toString()) { (k, v) ->
        listOf(esc(k), esc(v.reading), v.forms.joinToString(FORM.toString(), transform = ::esc)).joinToString(FIELD.toString())
    }

    fun decodeReadings(s: String): Map<String, ReadingOverride> {
        if (s.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ReadingOverride>()
        for (e in s.split(ENTRY)) {
            val f = e.split(FIELD)
            out[unesc(f[0])] = ReadingOverride(unesc(f[1]), forms(f[2]))
        }
        return out
    }

    private fun forms(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(FORM).map(::unesc)

    // separators and the escape character itself never appear raw inside a field
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace(ENTRY.toString(), "\\e")
        .replace(FIELD.toString(), "\\f").replace(FORM.toString(), "\\g")

    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 'e' -> ENTRY; 'f' -> FIELD; 'g' -> FORM; else -> s[i + 1] })
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
