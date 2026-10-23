package app.wanki.texts

/** One word of the text: what must be typed ([reading]) and other spellings that may be typed instead. */
data class TypingUnit(val reading: String, val forms: List<String> = emptyList())

enum class UnitState { OK, WRONG, SKIPPED, PENDING, UNTYPED }

/** [typed] and [extraBefore] are index ranges (inclusive) into the typed [String]. */
data class UnitResult(val state: UnitState, val typed: IntRange?, val extraBefore: IntRange?)

data class TypingResult(
    val units: List<UnitResult>,
    val trailingExtra: IntRange?,
    val end: Int,
    val total: Int,
    val correct: Int,
    val mistakes: Int,
) {
    val finished: Boolean get() = end == total
}

/**
 * Aligns typed text against the readings of [units] (edit distance: skipped reading char 2, wrong or extra
 * typed char 3; a whole unit can also be typed as one of its other spellings). The DP rows of the previous
 * [update] are kept, so appending a character computes one row. Each row only holds the columns within
 * [window] of the previous row's best end (a band): memory is typed × band, not typed × text, and a run that
 * skips less than [window] checked characters at once aligns exactly as with full rows.
 */
class TypingMatcher(private val units: List<TypingUnit>, private val window: Int = WINDOW) {
    private class Jump(val start: Int, val form: IntArray)

    private class Op(val kind: Int, val ai: Int, val bj: Int, val len: Int)

    private val unitStart = IntArray(units.size)
    private val unitEnd = IntArray(units.size)
    private val reading: IntArray
    private val byEnd: Array<MutableList<Jump>?>

    /** For each reading column, the non-empty unit that owns it. */
    private val columnUnit: IntArray
    private val m: Int
    private val width: Int

    /** The fewest typed characters that complete units from k on (the shortest spelling of each). */
    private val restFrom: IntArray

    /**
     * DP rows, one 16-bit array each holding columns [rowLo]..: costs saturate at [MAX_COST], a column outside
     * a row's band reads as [MAX_COST]. [rowBest] is each row's best end (the last column with the least cost).
     */
    private val rows = ArrayList<CharArray>()
    private var rowLo = IntArray(64)
    private var rowBest = IntArray(64)
    private val fold = IncrementalFold()
    private var prevT = IntArray(0)

    init {
        val all = ArrayList<Int>()
        val folds = units.map { TypingText.fold(it.reading).chars }
        for ((k, f) in folds.withIndex()) {
            unitStart[k] = all.size
            f.forEach(all::add)
            unitEnd[k] = all.size
        }
        reading = all.toIntArray()
        m = reading.size
        width = m + 1
        byEnd = arrayOfNulls(width)
        columnUnit = IntArray(m)
        restFrom = IntArray(units.size + 1)
        val shortest = IntArray(units.size) { folds[it].size }
        for ((k, u) in units.withIndex()) {
            for (c in unitStart[k] until unitEnd[k]) columnUnit[c] = k
            val own = folds[k].toList()
            for (form in u.forms) {
                val f = TypingText.fold(form).chars
                if (unitStart[k] == unitEnd[k] || f.isEmpty() || f.toList() == own) continue
                (byEnd[unitEnd[k]] ?: ArrayList<Jump>().also { byEnd[unitEnd[k]] = it }).add(Jump(unitStart[k], f))
                if (f.size < shortest[k]) shortest[k] = f.size
            }
        }
        for (k in units.indices.reversed()) restFrom[k] = restFrom[k + 1] + shortest[k]
        // row 0 is whole: skipping the first j reading chars
        rows.add(CharArray(width) { cost(2 * it) })
    }

    /** The fewest typed characters that could still finish the text from reading column [end]. */
    fun minToFinish(end: Int): Int {
        if (end >= m) return 0
        val k = columnUnit[end]
        return if (end == unitStart[k]) restFrom[k] else unitEnd[k] - end + restFrom[k + 1]
    }

    fun update(typed: String): TypingResult {
        var p = fold.update(typed)
        val folded = fold.folded()
        val t = folded.chars
        val n = t.size
        val limit = minOf(n, prevT.size)
        if (p > limit) p = limit
        while (p < limit && t[p] == prevT[p]) p++
        while (rows.size > n + 1) rows.removeAt(rows.size - 1)
        for (i in p + 1..n) computeRow(i, t)
        prevT = t
        val end = rowBest[n]
        return judge(typed, folded, end, traceback(t, n, end))
    }

    private fun cost(v: Int): Char = (if (v > MAX_COST) MAX_COST else v).toChar()

    /** The cost at row [i], column [j]; [MAX_COST] outside the row's band. */
    private fun at(i: Int, j: Int): Int {
        val row = rows[i]
        val k = j - rowLo[i]
        return if (k < 0 || k >= row.size) MAX_COST else row[k].code
    }

    private fun computeRow(i: Int, t: IntArray) {
        val center = rowBest[i - 1]
        val lo = maxOf(0L, center.toLong() - window).toInt()
        val hi = minOf(m.toLong(), center.toLong() + window).toInt()
        val row = CharArray(hi - lo + 1)
        if (rows.size <= i) rows.add(row) else rows[i] = row
        if (rowLo.size <= i) {
            rowLo = rowLo.copyOf(maxOf(i + 1, rowLo.size * 2))
            rowBest = rowBest.copyOf(rowLo.size)
        }
        rowLo[i] = lo
        var best = lo
        for (j in lo..hi) {
            var v = at(i - 1, j) + 3
            if (j > lo) {
                val del = row[j - 1 - lo].code + 2
                if (del < v) v = del
            }
            if (j > 0) {
                val diag = at(i - 1, j - 1) + if (t[i - 1] == reading[j - 1]) 0 else 3
                if (diag < v) v = diag
            }
            val jumps = byEnd[j]
            if (jumps != null) {
                for (jump in jumps) {
                    if (!formEndsAt(t, i, jump.form)) continue
                    val w = at(i - jump.form.size, jump.start)
                    if (w < v) v = w
                }
            }
            row[j - lo] = cost(v)
            if (row[j - lo] <= row[best - lo]) best = j
        }
        rowBest[i] = best
    }

    private fun formEndsAt(t: IntArray, i: Int, form: IntArray): Boolean {
        val from = i - form.size
        if (from < 0) return false
        for (k in form.indices) if (t[from + k] != form[k]) return false
        return true
    }

    /** The optimal path from (n, end) back to (0, 0), in text order. Ties resolve as in the forward pass. */
    private fun traceback(t: IntArray, n: Int, end: Int): List<Op> {
        val ops = ArrayList<Op>()
        var i = n
        var j = end
        while (i > 0 || j > 0) {
            val cur = at(i, j)
            if (j > 0 && cost(at(i, j - 1) + 2).code == cur) {
                ops.add(Op(DEL, -1, j - 1, 0)); j--
            } else if (i > 0 && j > 0 && cost(at(i - 1, j - 1) + (if (t[i - 1] == reading[j - 1]) 0 else 3)).code == cur) {
                ops.add(Op(if (t[i - 1] == reading[j - 1]) OK else SUB, i - 1, j - 1, 0)); i--; j--
            } else if (i > 0 && cost(at(i - 1, j) + 3).code == cur) {
                ops.add(Op(INS, i - 1, -1, 0)); i--
            } else {
                val jump = byEnd[j]?.firstOrNull { formEndsAt(t, i, it.form) && at(i - it.form.size, it.start) == cur }
                if (jump == null) {
                    // only a saturated cost can get here: step back as an extra char or a skipped one
                    if (i > 0) { ops.add(Op(INS, i - 1, -1, 0)); i-- } else { ops.add(Op(DEL, -1, j - 1, 0)); j-- }
                    continue
                }
                ops.add(Op(JUMP, i, jump.start, jump.form.size))
                i -= jump.form.size; j = jump.start
            }
        }
        ops.reverse()
        return ops
    }

    private fun judge(typed: String, folded: Folded, end: Int, ops: List<Op>): TypingResult {
        val count = units.size
        val colState = IntArray(m)                       // 0 untouched, OK, SUB, DEL
        val tMin = IntArray(count) { Int.MAX_VALUE }
        val tMax = IntArray(count) { -1 }
        val interiorExtra = BooleanArray(count)
        val exMin = IntArray(count) { Int.MAX_VALUE }
        val exMax = IntArray(count) { -1 }
        var pendMin = Int.MAX_VALUE
        var pendMax = -1

        fun addTyped(u: Int, a: Int) {
            if (a < tMin[u]) tMin[u] = a
            if (a > tMax[u]) tMax[u] = a
        }
        fun flush(col: Int) {
            if (pendMax < 0) return
            val u = columnUnit[col]
            if (col == unitStart[u]) {
                exMin[u] = pendMin; exMax[u] = pendMax
            } else {
                interiorExtra[u] = true
                addTyped(u, pendMin); addTyped(u, pendMax)
            }
            pendMin = Int.MAX_VALUE; pendMax = -1
        }

        for (op in ops) {
            when (op.kind) {
                INS -> { pendMin = minOf(pendMin, op.ai); pendMax = maxOf(pendMax, op.ai) }
                JUMP -> {
                    flush(op.bj)
                    val u = columnUnit[op.bj]
                    for (c in unitStart[u] until unitEnd[u]) colState[c] = OK
                    for (a in op.ai - op.len until op.ai) addTyped(u, a)
                }
                else -> {
                    flush(op.bj)
                    colState[op.bj] = op.kind
                    if (op.kind != DEL) addTyped(columnUnit[op.bj], op.ai)
                }
            }
        }

        fun range(lo: Int, hi: Int): IntRange? {
            if (hi < 0) return null
            val a = folded.source[lo]
            val b = folded.source[hi]
            return a..(b + Character.charCount(typed.codePointAt(b)) - 1)
        }

        val results = ArrayList<UnitResult>(count)
        var correct = 0
        var mistakes = 0
        var inSkip = false
        for (k in 0 until count) {
            val s = unitStart[k]
            val e = unitEnd[k]
            if (s == e) {
                results.add(UnitResult(if (s <= end) UnitState.OK else UnitState.UNTYPED, null, null))
                continue
            }
            val extraBefore = range(exMin[k], exMax[k])
            if (extraBefore != null) { mistakes++; inSkip = false }
            val typedRange = range(tMin[k], tMax[k])
            if (s >= end) {
                results.add(UnitResult(UnitState.UNTYPED, null, extraBefore))
                continue
            }
            if (end < e) {
                results.add(UnitResult(UnitState.PENDING, typedRange, extraBefore))
                continue
            }
            var allOk = !interiorExtra[k]
            var allDel = true
            for (c in s until e) {
                if (colState[c] != OK) allOk = false
                if (colState[c] != DEL) allDel = false
            }
            val state = when {
                allOk -> { correct++; inSkip = false; UnitState.OK }
                allDel -> { if (!inSkip) mistakes++; inSkip = true; UnitState.SKIPPED }
                else -> { mistakes++; inSkip = false; UnitState.WRONG }
            }
            results.add(UnitResult(state, typedRange, extraBefore))
        }
        val trailing = range(pendMin, pendMax)
        if (trailing != null) mistakes++
        return TypingResult(results, trailing, end, m, correct, mistakes)
    }

    companion object {
        const val WINDOW = 512
        private const val MAX_COST = 65535
        private const val OK = 1
        private const val SUB = 2
        private const val DEL = 3
        private const val INS = 4
        private const val JUMP = 5
    }
}
