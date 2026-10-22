package app.wanki.texts

/** Other ways to write a number in Japanese text: 3 ⇄ 三, 23 ⇄ 二十三 ⇄ 二三, 2026 ⇄ 二〇二六. */
object JapaneseNumbers {
    private const val DIGITS = "〇一二三四五六七八九"

    /** Spellings of [surface] other than itself; empty when it is not a number. */
    fun forms(surface: String): List<String> {
        if (surface.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        if (surface.all { Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() }) {
            val digits = surface.map { Character.digit(it, 10) }
            val n = digits.joinToString("").toIntOrNull()
            // positional first, as it is the usual way to write 1-99
            if (digits.size <= 2 && digits[0] != 0 && n != null) out += positional(n)
            out += digits.joinToString("") { DIGITS[it].toString() }
        } else if (surface.all { it in DIGITS || it == '十' }) {
            val n = fromKanji(surface) ?: return emptyList()
            out += n
        }
        out.remove(surface)
        return out.toList()
    }

    private fun positional(n: Int): String = when {
        n < 10 -> DIGITS[n].toString()
        n < 20 -> "十" + if (n % 10 == 0) "" else DIGITS[n % 10].toString()
        else -> DIGITS[n / 10].toString() + "十" + if (n % 10 == 0) "" else DIGITS[n % 10].toString()
    }

    private fun fromKanji(s: String): String? {
        if ('十' !in s) return s.map { DIGITS.indexOf(it) }.joinToString("")
        if (s.count { it == '十' } != 1) return null
        val tens = s.substringBefore('十')
        val ones = s.substringAfter('十')
        if (tens.length > 1 || ones.length > 1) return null
        val t = if (tens.isEmpty()) 1 else DIGITS.indexOf(tens[0])
        val o = if (ones.isEmpty()) 0 else DIGITS.indexOf(ones[0])
        if (t < 1 || (ones.isNotEmpty() && o < 1)) return null
        return (t * 10 + o).toString()
    }
}
