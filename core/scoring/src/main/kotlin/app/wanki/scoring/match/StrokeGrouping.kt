package app.wanki.scoring.match

import app.wanki.scoring.geom.Polyline
import app.wanki.scoring.geom.resample
import kotlin.math.min

/**
 * A set of user strokes that together write a set of reference strokes. Usually 1:1; handwriting
 * also produces 1:n (connected strokes, e.g. the last two strokes of 口 in one motion) and n:1
 * (a stroke lifted half-way).
 */
data class StrokeGroup(val users: List<Int>, val refs: List<Int>)

/**
 * Turns the 1:1 Hungarian matching into groups by absorbing leftover strokes when that clearly
 * improves the fit. Only neighbours are considered: reference strokes adjacent in stroke order,
 * user strokes adjacent in writing order — that is how connected and broken strokes happen.
 */
object StrokeGrouping {
    /** A merge must cut the group's misfit to this fraction (or below). */
    private const val IMPROVEMENT = 0.85

    fun group(
        user: List<Polyline>,
        ref: List<Polyline>,
        matches: List<StrokeMatch>,
        n: Int,
        size: Double,
        unmatchedCost: Double,
    ): List<StrokeGroup> {
        val groups = matches.map { StrokeGroup(listOf(it.userIndex), listOf(it.refIndex)) }.toMutableList()
        val freeRefs = (ref.indices.toSet() - matches.map { it.refIndex }.toSet()).toMutableSet()
        val freeUsers = (user.indices.toSet() - matches.map { it.userIndex }.toSet()).toMutableSet()
        val costCache = HashMap<StrokeGroup, Double>()
        fun cost(g: StrokeGroup) = costCache.getOrPut(g) { groupCost(g, user, ref, n) / size }

        var changed = true
        while (changed && (freeRefs.isNotEmpty() || freeUsers.isNotEmpty())) {
            changed = false
            for (gi in groups.indices) {
                val g = groups[gi]
                val candidates = buildList {
                    for (r in listOf(g.refs.min() - 1, g.refs.max() + 1)) {
                        if (r in freeRefs) add(g.copy(refs = (g.refs + r).sorted()))
                    }
                    for (u in listOf(g.users.min() - 1, g.users.max() + 1)) {
                        if (u in freeUsers) add(g.copy(users = (g.users + u).sorted()))
                    }
                }
                val best = candidates.minByOrNull(::cost) ?: continue
                if (cost(best) <= cost(g) * IMPROVEMENT) {
                    groups[gi] = best
                    freeRefs -= best.refs.toSet()
                    freeUsers -= best.users.toSet()
                    changed = true
                }
            }
            // a leftover user stroke that writes two leftover neighbouring reference strokes at once
            for (u in freeUsers.toList()) {
                val pair = freeRefs.filter { it + 1 in freeRefs }
                    .map { StrokeGroup(listOf(u), listOf(it, it + 1)) }
                    .minByOrNull(::cost) ?: continue
                if (cost(pair) < unmatchedCost * 1.5) {
                    groups += pair
                    freeUsers -= u
                    freeRefs -= pair.refs.toSet()
                    changed = true
                }
            }
        }
        return groups
    }

    /** Concatenation of strokes in the given order, resampled. */
    fun concat(lines: List<Polyline>, n: Int): Polyline = lines.flatten().resample(n)

    private fun groupCost(g: StrokeGroup, user: List<Polyline>, ref: List<Polyline>, n: Int): Double {
        val u = concat(g.users.map { user[it] }, n)
        val r = concat(g.refs.map { ref[it] }, n)
        return min(meanDist(u, r), meanDist(u.asReversed(), r))
    }

    internal fun meanDist(a: Polyline, b: Polyline): Double {
        var s = 0.0
        for (k in a.indices) s += a[k].dist(b[k])
        return s / a.size
    }
}
