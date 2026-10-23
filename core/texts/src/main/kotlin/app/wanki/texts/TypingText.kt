package app.wanki.texts

import java.text.Normalizer

/** The checked code points of a text, and for each the index of the [String] char it came from. */
class Folded(val chars: IntArray, val source: IntArray)

object TypingText {
    /** Characters that are never checked: punctuation, separators, symbols, control/format and combining marks. */
    fun isIgnored(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint)) return true
        return when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }

    internal fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    /**
     * NFKC, drop ignored characters, lowercase, letter variants, katakana to hiragana. A base character is
     * folded together with the marks after it (so decomposed か+゛ equals が); [Folded.source] is the base.
     */
    fun fold(s: String): Folded = IncrementalFold().apply { update(s) }.folded()

    /** Folds [s] from [from] (the start of a base and its marks), handing each folded code point and its base index to [emit]. */
    internal inline fun foldFrom(s: String, from: Int, emit: (Int, Int) -> Unit) {
        var i = from
        while (i < s.length) {
            val cp = s.codePointAt(i)
            var next = i + Character.charCount(cp)
            while (next < s.length && isMark(s.codePointAt(next))) next += Character.charCount(s.codePointAt(next))
            for (n in Normalizer.normalize(s.substring(i, next), Normalizer.Form.NFKC).codePoints()) {
                if (isIgnored(n)) continue
                for (lower in String(Character.toChars(n)).lowercase().codePoints()) {
                    if (isIgnored(lower)) continue
                    val folded = if (lower <= 0xFFFF) LetterFolding.fold(lower.toChar()) else String(Character.toChars(lower))
                    for (f in folded.codePoints()) emit(if (f in 0x30A1..0x30F6) f - 0x60 else f, i)
                }
            }
            i = next
        }
    }
}

/**
 * The fold of a text that changes at its end (typing, backspace): [update] refolds only from the base of the
 * first changed character, as a mark typed later belongs to the base before it.
 */
class IncrementalFold {
    private var text = ""
    private var chars = IntArray(64)
    private var source = IntArray(64)
    private var size = 0

    /** Folds [s]; the folded characters before the returned index are the same as in the previous fold. */
    fun update(s: String): Int {
        var p = 0
        val limit = minOf(s.length, text.length)
        while (p < limit && s[p] == text[p]) p++
        if (p == s.length && p == text.length) return size
        var from = p
        while (from > 0) {
            val cp = Character.codePointBefore(s, from)
            from -= Character.charCount(cp)
            if (!TypingText.isMark(cp)) break
        }
        while (size > 0 && source[size - 1] >= from) size--
        val kept = size
        TypingText.foldFrom(s, from) { c, i ->
            if (size == chars.size) {
                chars = chars.copyOf(size * 2)
                source = source.copyOf(size * 2)
            }
            chars[size] = c
            source[size] = i
            size++
        }
        text = s
        return kept
    }

    fun folded(): Folded = Folded(chars.copyOf(size), source.copyOf(size))
}
