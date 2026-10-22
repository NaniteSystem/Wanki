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

    /** NFKC, drop ignored characters, lowercase, letter variants, katakana to hiragana; per source character. */
    fun fold(s: String): Folded {
        val chars = ArrayList<Int>(s.length)
        val source = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            for (n in Normalizer.normalize(s.substring(i, i + len), Normalizer.Form.NFKC).codePoints()) {
                if (isIgnored(n)) continue
                for (lower in String(Character.toChars(n)).lowercase().codePoints()) {
                    if (isIgnored(lower)) continue
                    val folded = if (lower <= 0xFFFF) LetterFolding.fold(lower.toChar()) else String(Character.toChars(lower))
                    for (f in folded.codePoints()) {
                        chars.add(if (f in 0x30A1..0x30F6) f - 0x60 else f)
                        source.add(i)
                    }
                }
            }
            i += len
        }
        return Folded(chars.toIntArray(), source.toIntArray())
    }
}
