package app.wanki.texts

/** A number, maybe with a counter, as one word: its reading and every spelling accepted for it. */
data class NumberWord(val reading: String, val forms: List<String>)

/**
 * Japanese numbers. Every spelling of a number lists all the others (3 ⇄ 三, 23 ⇄ 二十三 ⇄ 二三,
 * 2026 ⇄ 二千二十六 ⇄ 二〇二六, 100 ⇄ 百 ⇄ 一〇〇), up to 99 999 999. A number with a counter from a small table
 * reads as one word (3月 さんがつ, 三日 みっか, 10分 じゅっぷん): the analyser splits them and gives the counter
 * its standalone reading (月 つき).
 */
object JapaneseNumbers {
    private const val DIGITS = "〇一二三四五六七八九"
    private const val MAX = 99_999_999L
    private val digitReading = arrayOf("ぜろ", "いち", "に", "さん", "よん", "ご", "ろく", "なな", "はち", "きゅう")

    /**
     * How a counter reads after a number: [whole] numbers with their own readings (the first is the reading,
     * the rest are accepted too), sound changes after the last digit ([ones]) or after a round ten ([ten]),
     * otherwise the digit's reading and [suffix].
     */
    private class Counter(
        val suffix: String,
        val ones: Map<Int, String> = emptyMap(),
        val ten: String? = null,
        val whole: Map<Long, List<String>> = emptyMap(),
    )

    /** The common counters only: months, years, days, people, hours, minutes, times, pieces, age, yen. */
    private val counters = mapOf(
        "月" to Counter("がつ", ones = mapOf(4 to "しがつ", 7 to "しちがつ", 9 to "くがつ")),
        "年" to Counter("ねん", ones = mapOf(4 to "よねん")),
        "日" to Counter(
            "にち", ones = mapOf(4 to "よっか", 7 to "しちにち", 9 to "くにち"),
            whole = mapOf(
                1L to listOf("ついたち", "いちにち"), 2L to listOf("ふつか"), 3L to listOf("みっか"), 4L to listOf("よっか"),
                5L to listOf("いつか"), 6L to listOf("むいか"), 7L to listOf("なのか"), 8L to listOf("ようか"),
                9L to listOf("ここのか"), 10L to listOf("とおか"), 20L to listOf("はつか"),
            ),
        ),
        "人" to Counter("にん", ones = mapOf(4 to "よにん"), whole = mapOf(1L to listOf("ひとり"), 2L to listOf("ふたり"))),
        "時" to Counter("じ", ones = mapOf(4 to "よじ", 7 to "しちじ", 9 to "くじ")),
        "分" to Counter("ふん", ones = mapOf(1 to "いっぷん", 3 to "さんぷん", 4 to "よんぷん", 6 to "ろっぷん", 8 to "はっぷん"), ten = "じゅっぷん"),
        "回" to Counter("かい", ones = mapOf(1 to "いっかい", 6 to "ろっかい", 8 to "はっかい"), ten = "じゅっかい"),
        "個" to Counter("こ", ones = mapOf(1 to "いっこ", 6 to "ろっこ", 8 to "はっこ"), ten = "じゅっこ"),
        "歳" to Counter("さい", ones = mapOf(1 to "いっさい", 8 to "はっさい"), ten = "じゅっさい", whole = mapOf(20L to listOf("はたち", "にじゅっさい"))),
        "円" to Counter("えん", ones = mapOf(4 to "よえん")),
    )

    /** Digits (any script) or kanji numerals only. */
    fun isNumber(s: String): Boolean = s.isNotEmpty() && s.all { Character.isDigit(it) || it in DIGITS || it in "十百千万" }

    fun isCounter(s: String): Boolean = s in counters

    /** Spellings of [surface] other than itself; empty when it is not a number. */
    fun forms(surface: String): List<String> = spellings(surface)?.let { (it - surface).toList() } ?: emptyList()

    /** [number] followed by [counter] (empty for none) as one word; null when it is not a number or the counter is unknown. */
    fun word(number: String, counter: String = ""): NumberWord? {
        val digits = digitString(number) ?: return null
        val c = if (counter.isEmpty()) null else counters[counter] ?: return null
        val spellings = spellings(number)!!
        val forms = LinkedHashSet<String>()
        spellings.forEach { forms += it + counter }
        if (!hasValue(digits)) {
            // a code such as 03, or a long digit string: read digit by digit
            return NumberWord(digits.map { digitReading[it - '0'] }.joinToString("") + (c?.suffix ?: ""), forms.toList())
        }
        val n = digits.toLong()
        val plain = reading(n)
        if (c == null) return NumberWord(plain, forms.toList())
        val readings = c.whole[n] ?: listOf(withCounter(n, c))
        // digits with the counter in kana (3がつ) when the number keeps its own reading
        readings.forEach { r -> if (r.startsWith(plain)) forms += digits + r.substring(plain.length) }
        forms += readings.drop(1)
        return NumberWord(readings[0], forms.toList())
    }

    private fun withCounter(n: Long, c: Counter): String {
        val ones = (n % 10).toInt()
        val tens = (n / 10 % 10).toInt()
        return when {
            ones != 0 -> prefix(n - ones) + (c.ones[ones] ?: (digitReading[ones] + c.suffix))
            tens != 0 && c.ten != null -> prefix(n - 10 * tens) + (if (tens > 1) digitReading[tens] else "") + c.ten
            else -> reading(n) + c.suffix
        }
    }

    private fun prefix(n: Long): String = if (n == 0L) "" else reading(n)

    /** Kana reading of [n] (0 ≤ n ≤ [MAX]). */
    private fun reading(n: Long): String {
        if (n == 0L) return digitReading[0]
        val man = n / 10_000
        return (if (man > 0) below10000Reading(man.toInt()) + "まん" else "") + below10000Reading((n % 10_000).toInt())
    }

    private fun below10000Reading(x: Int): String {
        val sb = StringBuilder()
        when (val d = x / 1000) {
            0 -> {}
            1 -> sb.append("せん")
            3 -> sb.append("さんぜん")
            8 -> sb.append("はっせん")
            else -> sb.append(digitReading[d]).append("せん")
        }
        when (val d = x / 100 % 10) {
            0 -> {}
            1 -> sb.append("ひゃく")
            3 -> sb.append("さんびゃく")
            6 -> sb.append("ろっぴゃく")
            8 -> sb.append("はっぴゃく")
            else -> sb.append(digitReading[d]).append("ひゃく")
        }
        when (val d = x / 10 % 10) {
            0 -> {}
            1 -> sb.append("じゅう")
            else -> sb.append(digitReading[d]).append("じゅう")
        }
        if (x % 10 != 0) sb.append(digitReading[x % 10])
        return sb.toString()
    }

    /** Every spelling of the number [surface] is, [surface] included: ASCII digits, positional kanji, kanji digit by digit. */
    private fun spellings(surface: String): LinkedHashSet<String>? {
        val digits = digitString(surface) ?: return null
        val out = LinkedHashSet<String>()
        out += surface
        out += digits
        if (hasValue(digits)) out += positional(digits.toLong())
        out += digits.map { DIGITS[it - '0'] }.joinToString("")
        return out
    }

    /** Read as a number (up to [MAX], no leading zero) rather than digit by digit. */
    private fun hasValue(digits: String) = digits.length <= 8 && (digits.length == 1 || digits[0] != '0')

    /** The number as ASCII digits; null when [s] is not a number. */
    private fun digitString(s: String): String? {
        if (s.isEmpty()) return null
        if (s.all { Character.isDigit(it) }) return s.map { '0' + Character.digit(it, 10) }.joinToString("")
        if (s.all { it in DIGITS }) return s.map { '0' + DIGITS.indexOf(it) }.joinToString("")
        return fromPositional(s)?.toString()
    }

    private fun positional(n: Long): String {
        if (n == 0L) return DIGITS[0].toString()
        val man = n / 10_000
        return (if (man > 0) below10000(man.toInt()) + "万" else "") + below10000((n % 10_000).toInt())
    }

    private fun below10000(x: Int): String {
        val sb = StringBuilder()
        for ((place, ch) in listOf(1000 to '千', 100 to '百', 10 to '十')) {
            val d = x / place % 10
            if (d > 1) sb.append(DIGITS[d])
            if (d > 0) sb.append(ch)
        }
        if (x % 10 != 0) sb.append(DIGITS[x % 10])
        return sb.toString()
    }

    /** 二千二十六 → 2026; null when [s] is not a well-formed positional number. */
    private fun fromPositional(s: String): Long? {
        if (s.count { it == '万' } > 1) return null
        val high = s.substringBefore('万', "")
        val low = s.substringAfter('万', s)
        if ('万' in s && high.isEmpty()) return null
        val h = if (high.isEmpty()) 0 else parseBelow10000(high) ?: return null
        val l = if (low.isEmpty()) 0 else parseBelow10000(low) ?: return null
        if ('万' in s && h == 0) return null
        return h * 10_000L + l
    }

    private fun parseBelow10000(s: String): Int? {
        var total = 0
        var pending = -1
        var lastPlace = 10_000
        for (ch in s) {
            val d = DIGITS.indexOf(ch)
            if (d >= 0) {
                if (pending >= 0) return null
                pending = d
                continue
            }
            val place = when (ch) { '千' -> 1000; '百' -> 100; '十' -> 10; else -> return null }
            if (place >= lastPlace || pending == 0) return null
            total += (if (pending < 0) 1 else pending) * place
            pending = -1
            lastPlace = place
        }
        if (pending >= 0) total += pending
        return total
    }
}
