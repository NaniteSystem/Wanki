package app.wanki.scoring.match

import app.wanki.scoring.geom.Polyline
import kotlin.math.min

data class StrokeMatch(
    val userIndex: Int,
    val refIndex: Int,
    /** The user drew this stroke end-to-start. */
    val reversed: Boolean,
    /** Mean point distance (direction-aligned) in units of the reference square side. */
    val cost: Double,
)

/**
 * Pairs user strokes with reference strokes by the Hungarian algorithm.
 * Both inputs must be normalized into the same frame and resampled to the same point count.
 *
 * Unmatched strokes are modelled explicitly: the (n+m)×(n+m) matrix lets every stroke stay
 * unpaired at [unmatchedCost], so a pair is only formed when its cost is below 2×[unmatchedCost].
 */
object StrokeMatcher {
    fun match(user: List<Polyline>, ref: List<Polyline>, size: Double, unmatchedCost: Double): List<StrokeMatch> {
        val n = user.size
        val m = ref.size
        if (n == 0 || m == 0) return emptyList()
        val big = 1e6
        val fwd = Array(n) { DoubleArray(m) }
        val rev = Array(n) { DoubleArray(m) }
        for (i in 0 until n) {
            val ur = user[i].asReversed()
            for (j in 0 until m) {
                fwd[i][j] = meanDist(user[i], ref[j]) / size
                rev[i][j] = meanDist(ur, ref[j]) / size
            }
        }
        val dim = n + m
        val cost = Array(dim) { r ->
            DoubleArray(dim) { c ->
                when {
                    r < n && c < m -> min(fwd[r][c], rev[r][c])
                    r < n -> if (c - m == r) unmatchedCost else big // user stroke r left unpaired
                    c < m -> if (r - n == c) unmatchedCost else big // ref stroke c left unpaired
                    else -> 0.0
                }
            }
        }
        val assign = hungarian(cost)
        val out = ArrayList<StrokeMatch>()
        for (i in 0 until n) {
            val j = assign[i]
            if (j in 0 until m) {
                val reversed = rev[i][j] < fwd[i][j]
                out += StrokeMatch(i, j, reversed, min(fwd[i][j], rev[i][j]))
            }
        }
        return out
    }

    private fun meanDist(a: Polyline, b: Polyline): Double {
        var s = 0.0
        for (k in a.indices) s += a[k].dist(b[k])
        return s / a.size
    }
}

/** Indices (into [seq]) of one longest strictly increasing subsequence. */
internal fun longestIncreasingSubsequence(seq: List<Int>): Set<Int> {
    if (seq.isEmpty()) return emptySet()
    val tailIdx = ArrayList<Int>()
    val prev = IntArray(seq.size) { -1 }
    for (i in seq.indices) {
        var lo = 0
        var hi = tailIdx.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (seq[tailIdx[mid]] < seq[i]) lo = mid + 1 else hi = mid
        }
        if (lo > 0) prev[i] = tailIdx[lo - 1]
        if (lo == tailIdx.size) tailIdx += i else tailIdx[lo] = i
    }
    val result = HashSet<Int>()
    var k = tailIdx.last()
    while (k >= 0) { result += k; k = prev[k] }
    return result
}

/** Dynamic time warping; returns the mean point distance along the optimal path. */
internal fun dtwMean(a: Polyline, b: Polyline): Double {
    val n = a.size
    val m = b.size
    val inf = Double.POSITIVE_INFINITY
    val d = Array(n + 1) { DoubleArray(m + 1) { inf } }
    val len = Array(n + 1) { IntArray(m + 1) }
    d[0][0] = 0.0
    for (i in 1..n) for (j in 1..m) {
        val c = a[i - 1].dist(b[j - 1])
        var best = d[i - 1][j - 1]; var bl = len[i - 1][j - 1]
        if (d[i - 1][j] < best) { best = d[i - 1][j]; bl = len[i - 1][j] }
        if (d[i][j - 1] < best) { best = d[i][j - 1]; bl = len[i][j - 1] }
        d[i][j] = best + c
        len[i][j] = bl + 1
    }
    return d[n][m] / len[n][m]
}
