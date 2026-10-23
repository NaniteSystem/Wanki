package app.wanki.texts

import kotlin.system.measureNanoTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypingMatcherTest {
    private fun words(vararg w: String) = w.map { TypingUnit(it) }
    private val abc = words("abc", "def", "ghi")

    private fun TypingResult.states() = units.map { it.state }

    @Test
    fun exactTypingIsAllOk() {
        val r = TypingMatcher(abc).update("abc def ghi")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(0, r.mistakes)
        assertEquals(3, r.correct)
        assertTrue(r.finished)
    }

    @Test
    fun wrongCharMakesWordWrong() {
        val r = TypingMatcher(abc).update("abc dxf ghi")
        assertEquals(listOf(UnitState.OK, UnitState.WRONG, UnitState.OK), r.states())
        assertTrue(5 in r.units[1].typed!!)
        assertEquals(1, r.mistakes)
        assertEquals(2, r.correct)
    }

    @Test
    fun skippedWordIsSkipped() {
        val r = TypingMatcher(abc).update("abc ghi")
        assertEquals(listOf(UnitState.OK, UnitState.SKIPPED, UnitState.OK), r.states())
        assertNull(r.units[1].typed)
        assertEquals(1, r.mistakes)
    }

    // The word after the skipped run must be long: substituting a short word (3/char) beats skipping (2/char).
    @Test
    fun runOfSkippedWordsCountsOnce() {
        val r = TypingMatcher(words("abc", "def", "ghi", "jkl", "mnopqrstuv")).update("abc mnopqrstuv")
        assertEquals(
            listOf(UnitState.OK, UnitState.SKIPPED, UnitState.SKIPPED, UnitState.SKIPPED, UnitState.OK),
            r.states(),
        )
        assertEquals(1, r.mistakes)
    }

    @Test
    fun extraWordIsExtra() {
        val r = TypingMatcher(abc).update("abc xyz def ghi")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(4..6, r.units[1].extraBefore)
        assertEquals(1, r.mistakes)
    }

    @Test
    fun otherSpellingCounts() {
        val units = listOf(TypingUnit("すべて", listOf("全て")), TypingUnit("の"))
        val r = TypingMatcher(units).update("全ての")
        assertEquals(listOf(UnitState.OK, UnitState.OK), r.states())
        assertEquals(0, r.mistakes)
        assertEquals(0..1, r.units[0].typed)
    }

    @Test
    fun otherSpellingNotRedWhileIncomplete() {
        val units = listOf(TypingUnit("すべて", listOf("全て")), TypingUnit("の"))
        val r = TypingMatcher(units).update("全")
        assertEquals(listOf(UnitState.PENDING, UnitState.UNTYPED), r.states())
        assertEquals(0, r.mistakes)
    }

    @Test
    fun wordUnderCaretIsPending() {
        val r = TypingMatcher(listOf(TypingUnit("すべて"), TypingUnit("の"))).update("すべ")
        assertEquals(listOf(UnitState.PENDING, UnitState.UNTYPED), r.states())
    }

    @Test
    fun punctuationOnlyUnitsAreSkippedSilently() {
        val units = listOf(TypingUnit("abc"), TypingUnit(""), TypingUnit("def"))
        val r = TypingMatcher(units).update("abcdef")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(0, r.mistakes)
        assertEquals(2, r.correct)
        val empty = TypingMatcher(emptyList()).update("")
        assertTrue(empty.finished)
        assertEquals(0, empty.total)
    }

    @Test
    fun backspaceRecomputes() {
        val m = TypingMatcher(listOf(TypingUnit("すべて")))
        assertNotNull(m.update("すべてx").trailingExtra)
        val r = m.update("すべて")
        assertNull(r.trailingExtra)
        assertEquals(UnitState.OK, r.units[0].state)
    }

    @Test
    fun pasteInTheMiddleRecomputes() {
        val m = TypingMatcher(abc)
        m.update("abc")
        assertEquals(TypingMatcher(abc).update("xbc"), m.update("xbc"))
    }

    @Test
    fun speed3000() {
        val rnd = Random(7)
        val kana = "あいうえおかきくけこさしすせそたちつてと"
        val units = List(600) { TypingUnit(String(CharArray(5) { kana[rnd.nextInt(kana.length)] })) }
        val text = units.joinToString("") { it.reading }
        val m = TypingMatcher(units)
        val times = LongArray(text.length)
        for (i in text.indices) {
            val typed = text.substring(0, i + 1)
            times[i] = measureNanoTime { m.update(typed) }
        }
        val meanMs = times.takeLast(1000).average() / 1e6
        assertTrue(meanMs < 5.0, "mean update $meanMs ms")
        val r = m.update(text)
        assertEquals(TypingMatcher(units).update(text), r)
        assertTrue(r.finished)
        assertEquals(600, r.correct)
    }

    @Test
    fun pasteLaterInTheTextReusesRows() {
        val u = words("abcdef", "ghijkl")
        val m = TypingMatcher(u)
        m.update("abcdefghi")
        assertEquals(TypingMatcher(u).update("abcxefghi"), m.update("abcxefghi"))
    }

    @Test
    fun extraCharInsideWordMakesItWrong() {
        val r = TypingMatcher(abc).update("abc dxef ghi")
        assertEquals(UnitState.WRONG, r.units[1].state)
        assertEquals(4..7, r.units[1].typed)
    }

    @Test
    fun rangesSkipIgnoredCharsAndSurrogates() {
        val r = TypingMatcher(listOf(TypingUnit("a\uD83D\uDE00b"), TypingUnit("c"))).update("a, b c")
        assertEquals(UnitState.OK, r.units[1].state)
        assertEquals(5..5, r.units[1].typed)
    }

    @Test
    fun hugeRunCompletes() {
        val rnd = Random(3)
        val kana = "あいうえおかきくけこさしすせそ"
        val units = List(1200) { TypingUnit(String(CharArray(5) { kana[rnd.nextInt(kana.length)] })) }
        val text = units.joinToString("") { it.reading }
        val r = TypingMatcher(units).update(text)
        assertTrue(r.finished)
        assertEquals(1200, r.correct)
        assertEquals(6000, r.total)
    }

    @Test
    fun markTypedAfterItsBaseRefoldsTheBase() {
        val u = listOf(TypingUnit("が"), TypingUnit("く"))
        val m = TypingMatcher(u)
        m.update("か")
        assertEquals(TypingMatcher(u).update("か\u3099"), m.update("か\u3099"))
    }

    /** Every case above, typed key by key: the band never changes a result. */
    @Test
    fun bandedEqualsUnbandedOnTheCases() {
        val cases = listOf(
            abc to "abc def ghi", abc to "abc dxf ghi", abc to "abc ghi", abc to "abc xyz def ghi", abc to "abc dxef ghi",
            words("abc", "def", "ghi", "jkl", "mnopqrstuv") to "abc mnopqrstuv",
            listOf(TypingUnit("すべて", listOf("全て")), TypingUnit("の")) to "全ての",
            listOf(TypingUnit("abc"), TypingUnit(""), TypingUnit("def")) to "abcdef",
            listOf(TypingUnit("a\uD83D\uDE00b"), TypingUnit("c")) to "a, b c",
        )
        for ((units, typed) in cases) {
            for (window in listOf(16, TypingMatcher.WINDOW)) assertSameAsUnbanded(units, typed, window)
        }
    }

    /** Normal typing of a long text (typos, a skipped word, an extra word, backspaces) under a small band. */
    @Test
    fun bandedEqualsUnbandedOnNormalTyping() {
        val rnd = Random(5)
        val kana = "あいうえおかきくけこさしすせそたちつてと"
        val units = List(400) { TypingUnit(String(CharArray(2 + rnd.nextInt(6)) { kana[rnd.nextInt(kana.length)] })) }
        val typed = StringBuilder()
        for (u in units) {
            when (rnd.nextInt(20)) {
                0 -> continue                                            // skipped word
                1 -> typed.append("ぬぬ ")                                // extra word
                2 -> typed.append(u.reading.dropLast(1)).append('ん')    // typo
                else -> typed.append(u.reading)
            }
            typed.append(' ')
        }
        for (window in listOf(16, TypingMatcher.WINDOW)) assertSameAsUnbanded(units, typed.toString(), window, backspaces = true)
    }

    private fun assertSameAsUnbanded(units: List<TypingUnit>, typed: String, window: Int, backspaces: Boolean = false) {
        val full = TypingMatcher(units, window = Int.MAX_VALUE)
        val banded = TypingMatcher(units, window = window)
        for (i in 1..typed.length) {
            val t = typed.substring(0, i)
            assertEquals(full.update(t), banded.update(t), "window $window at $i: $t")
            if (backspaces && i % 37 == 0) {
                val back = t.dropLast(3)
                assertEquals(full.update(back), banded.update(back), "window $window back at $i")
            }
        }
    }

    /** Memory is typed × band, not typed × text: a 20 000-char text typed to the end fits the test heap. */
    @Test
    fun longTextTypedFullyFitsTheHeap() {
        val (units, text) = longText(4000)
        val m = TypingMatcher(units)
        var r: TypingResult? = null
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + 1 + i % 5)
            r = m.update(text.substring(0, i))
        }
        assertTrue(r!!.finished)
        assertEquals(4000, r.correct)
        assertEquals(20_000, r.total)
    }

    /** A resumed draft of a long text is matched at once, without running out of memory. */
    @Test
    fun longDraftRestoredAtOnce() {
        val (units, text) = longText(4000)
        val r = TypingMatcher(units).update(text.substring(0, 18_000))
        // 3000 words of five letters and a space
        assertEquals(3000, r.correct)
        assertEquals(15_000, r.end)
    }

    private fun longText(words: Int): Pair<List<TypingUnit>, String> {
        val rnd = Random(9)
        val letters = "абвгдежзиклмнопрстуфхцчшщэюя"
        val units = List(words) { TypingUnit(String(CharArray(5) { letters[rnd.nextInt(letters.length)] })) }
        return units to units.joinToString(" ") { it.reading }
    }
}
