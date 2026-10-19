package app.wanki.ui.texts

import app.wanki.texts.PieceSplitter
import app.wanki.texts.TextKind

/** Where a re-split piece comes from: the draft it continues ([old], keeps id and note), else the draft whose schedule it copies. */
internal data class PieceOrigin(val old: Int?, val splitFrom: Int? = null)

/** Editor helpers: the whole-text field ↔ pieces that keep their ids across edits. */
internal object PieceDrafts {
    private val content = Regex("[\\p{L}\\p{N}]")

    fun hasContent(s: String) = content.containsMatchIn(s)

    /** Pieces of the whole-text field; a line equal to a piece in [keep] stays whole (a join made by hand). */
    fun split(text: String, kind: TextKind, keep: Set<String> = emptySet()): List<String> =
        text.lines().flatMap { line ->
            val t = line.trim()
            if (t in keep && hasContent(t)) listOf(t) else PieceSplitter.split(line, kind)
        }

    /**
     * Where "cut at the cursor" cuts: inside a word of a spaced language the nearer word edge, so a cut and a
     * later join give the text back; languages without spaces cut at the cursor itself.
     */
    fun cutPoint(text: String, cursor: Int, noSpaces: Boolean): Int {
        val at = cursor.coerceIn(0, text.length)
        if (noSpaces) return at
        var start = at
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = at
        while (end < text.length && !text[end].isWhitespace()) end++
        if (start == at || end == at) return at
        return if (at - start <= end - at) start else end
    }

    /**
     * Aligns [new] pieces with [old] ones: equal pieces (longest common subsequence) keep their draft; within
     * each gap between them pieces pair up in order (an edited piece), extra new ones copy the schedule of the
     * gap's last old piece (a split) and extra old ones are dropped (a join). A gap without old pieces is new text.
     */
    fun align(old: List<String>, new: List<String>): List<PieceOrigin> {
        // typing touches one place: match the common head and tail first, then LCS over the middle
        var pre = 0
        while (pre < old.size && pre < new.size && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < old.size - pre && suf < new.size - pre && old[old.size - 1 - suf] == new[new.size - 1 - suf]) suf++
        val o = old.subList(pre, old.size - suf)
        val n = new.subList(pre, new.size - suf)
        val lcs = Array(o.size + 1) { IntArray(n.size + 1) }
        for (i in o.indices.reversed()) for (j in n.indices.reversed()) {
            lcs[i][j] = if (o[i] == n[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<PieceOrigin>(new.size)
        repeat(pre) { out += PieceOrigin(it) }
        val gapOld = ArrayList<Int>()
        var gapNew = 0
        fun flush() {
            for (k in 0 until gapNew) {
                out += if (k < gapOld.size) PieceOrigin(gapOld[k]) else PieceOrigin(null, gapOld.lastOrNull())
            }
            gapOld.clear()
            gapNew = 0
        }
        var i = 0
        var j = 0
        while (i < o.size || j < n.size) {
            when {
                i < o.size && j < n.size && o[i] == n[j] -> { flush(); out += PieceOrigin(pre + i); i++; j++ }
                j < n.size && (i == o.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> { gapNew++; j++ }
                else -> { gapOld += pre + i; i++ }
            }
        }
        flush()
        repeat(suf) { out += PieceOrigin(old.size - suf + it) }
        return out
    }
}
