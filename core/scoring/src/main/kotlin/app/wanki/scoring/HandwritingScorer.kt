package app.wanki.scoring

import app.wanki.scoring.geom.BBox
import app.wanki.scoring.geom.Point
import app.wanki.scoring.geom.Polyline
import app.wanki.scoring.geom.Similarity
import app.wanki.scoring.geom.centroid
import app.wanki.scoring.geom.clamp01
import app.wanki.scoring.geom.length
import app.wanki.scoring.geom.resample
import app.wanki.scoring.match.StrokeMatch
import app.wanki.scoring.match.StrokeMatcher
import app.wanki.scoring.match.dtwMean
import app.wanki.scoring.match.longestIncreasingSubsequence
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Scores one handwritten character against its reference.
 *
 * Pipeline: normalize the attempt into the reference frame → resample every stroke →
 * Hungarian matching → metrics (shape, position/size, count, order, direction) →
 * recognition gate → handwriting score for passed attempts.
 *
 * Every per-stroke metric is averaged over the *reference* strokes with missing ones counted
 * as 0, so writing one stroke of ten perfectly does not score high.
 */
class HandwritingScorer(val config: ScoringConfig = ScoringConfig()) {

    /**
     * @param strokes the attempt in any coordinate system with y pointing down.
     * @param recognitionCandidates ranked recognizer output (e.g. ML Kit Digital Ink), or null if unavailable.
     * @param competitors references of look-alike characters (recognizer candidates ranked above the target,
     *   entries of a confusables table). The clamped score barely separates 土/士 or 末/未, but the raw
     *   [fitDistance] reliably says which reference fits better: if a competitor fits better by
     *   [ScoringConfig.competitorMargin], the attempt is that other character and the score is capped below the threshold.
     */
    fun score(
        reference: CharReference,
        strokes: List<Polyline>,
        recognitionCandidates: List<String>? = null,
        competitors: List<CharReference> = emptyList(),
    ): ScoreResult {
        val size = reference.size
        val n = config.resamplePoints
        val refRaw = reference.strokes.map { it.points }
        val refPolys = refRaw.map { it.resample(n) }
        val userIndices = strokes.indices.filter { strokes[it].isNotEmpty() }
        val recognition = when {
            recognitionCandidates == null -> RecognitionOutcome.NOT_CHECKED
            reference.char in recognitionCandidates.take(config.recognitionTopK) -> RecognitionOutcome.IN_TOP_K
            else -> RecognitionOutcome.NOT_RECOGNIZED
        }
        if (userIndices.isEmpty()) {
            return ScoreResult(
                char = reference.char,
                score = 0.0,
                rawScore = 0.0,
                passed = false,
                metrics = MetricScores(0.0, 0.0, 0.0, 0.0, 0.0),
                recognition = recognition,
                beauty = null,
                strokes = emptyList(),
                missingRefStrokes = reference.strokes.indices.toList(),
                toReference = null,
            )
        }

        val transform = fitToReference(userIndices.flatMap { strokes[it] }, refRaw.flatten())
        val userNorm = userIndices.map { transform.apply(strokes[it]) }
        val userPolys = userNorm.map { it.resample(n) }
        val matches = StrokeMatcher.match(userPolys, refPolys, size, config.unmatchedCost)

        val refCount = refPolys.size.toDouble()
        val inOrder = longestIncreasingSubsequence(matches.sortedBy { it.userIndex }.map { it.refIndex })
            .map { matches.sortedBy { m -> m.userIndex }[it].userIndex }.toSet()

        val tol = config.tolerances
        val pairs = matches.map { m -> PairScores(m, shapeScore(m, userPolys, refPolys, size, tol), positionScore(m, userPolys, refPolys, size, tol)) }
        val directionOk = matches.count { !it.reversed || isDirectionless(refRaw[it.refIndex], size) }
        val extra = userPolys.size - matches.size
        val missing = refPolys.size - matches.size

        val metrics = MetricScores(
            shape = pairs.sumOf { it.shape } / refCount,
            position = pairs.sumOf { it.position } / refCount,
            count = clamp01(1.0 - (missing + extra) / refCount),
            order = inOrder.size / refCount,
            direction = directionOk / refCount,
        )
        val w = config.weights
        val orderW = w.order * config.orderStrictness.coerceIn(0.0, 1.0)
        val totalW = w.shape + w.position + w.count + orderW + w.direction
        val raw = 100.0 * (w.shape * metrics.shape + w.position * metrics.position + w.count * metrics.count +
            orderW * metrics.order + w.direction * metrics.direction) / totalW
        val confusedWith = competitors.filter { it.char != reference.char }.takeIf { it.isNotEmpty() }?.let { cs ->
            val own = fitDistance(reference, strokes)
            cs.map { it to fitDistance(it, strokes) }
                .filter { (_, d) -> d < own * (1 - config.competitorMargin) }
                .minByOrNull { (_, d) -> d }
                ?.first?.char
        }
        val capped = recognition == RecognitionOutcome.NOT_RECOGNIZED || confusedWith != null
        val score = if (capped) min(raw, config.passThreshold - 1) else raw
        val passed = score >= config.passThreshold

        val feedback = ArrayList<UserStrokeFeedback>()
        val byUser = pairs.associateBy { it.match.userIndex }
        for (k in userPolys.indices) {
            val p = byUser[k]
            feedback += if (p == null) {
                UserStrokeFeedback(userIndices[k], null, StrokeStatus.EXTRA)
            } else {
                val wrongOrder = k !in inOrder && config.orderStrictness > 0
                val reversed = p.match.reversed && !isDirectionless(refRaw[p.match.refIndex], size)
                UserStrokeFeedback(
                    userIndices[k], p.match.refIndex,
                    if (wrongOrder || reversed) StrokeStatus.ORDER_OR_DIRECTION else StrokeStatus.CORRECT,
                    wrongOrder, reversed, p.shape, p.position,
                )
            }
        }
        val matchedRefs = matches.map { it.refIndex }.toSet()

        val beauty = if (passed) beauty(reference, userNorm, userPolys, refPolys, matches) else null
        return ScoreResult(
            char = reference.char,
            score = score,
            rawScore = raw,
            passed = passed,
            metrics = metrics,
            recognition = recognition,
            confusedWith = confusedWith,
            beauty = beauty,
            strokes = feedback,
            missingRefStrokes = refPolys.indices.filter { it !in matchedRefs },
            toReference = transform,
            needsHandwritingPractice = beauty != null && beauty.score < config.beautyPracticeThreshold,
        )
    }

    /**
     * Unclamped geometric misfit of the attempt to [reference]: mean of the matched pair costs plus stroke
     * extent differences, with every unmatched stroke counted at [ScoringConfig.unmatchedCost]×2.
     * Used to rank look-alike references.
     */
    fun fitDistance(reference: CharReference, strokes: List<Polyline>): Double {
        val n = config.resamplePoints
        val user = strokes.filter { it.isNotEmpty() }
        if (user.isEmpty()) return Double.POSITIVE_INFINITY
        val refRaw = reference.strokes.map { it.points }
        val t = fitToReference(user.flatten(), refRaw.flatten())
        val userPolys = user.map { t.apply(it).resample(n) }
        val refPolys = refRaw.map { it.resample(n) }
        val matches = StrokeMatcher.match(userPolys, refPolys, reference.size, config.unmatchedCost)
        val unmatched = (userPolys.size - matches.size) + (refRaw.size - matches.size)
        // Stroke extents are what tells most look-alikes apart (土/士, 末/未), so they are added explicitly.
        val pairCost = matches.sumOf { m ->
            val ub = BBox.of(userPolys[m.userIndex])
            val rb = BBox.of(refPolys[m.refIndex])
            m.cost + (abs(ub.width - rb.width) + abs(ub.height - rb.height)) / reference.size
        }
        return (pairCost + unmatched * 2 * config.unmatchedCost) / maxOf(userPolys.size, refRaw.size)
    }

    private class PairScores(val match: StrokeMatch, val shape: Double, val position: Double)

    /** Aspect-preserving fit of the attempt's bounding box onto the reference's (largest sides equal, centers aligned). */
    private fun fitToReference(user: List<Point>, ref: List<Point>): Similarity {
        val ub = BBox.of(user)
        val rb = BBox.of(ref)
        val scale = if (ub.maxSide < 1e-9) 1.0 else rb.maxSide / ub.maxSide
        val c = ub.center
        val rc = rb.center
        return Similarity(scale, rc.x - c.x * scale, rc.y - c.y * scale)
    }

    private fun aligned(m: StrokeMatch, user: List<Polyline>): Polyline =
        if (m.reversed) user[m.userIndex].asReversed() else user[m.userIndex]

    private fun shapeScore(m: StrokeMatch, user: List<Polyline>, ref: List<Polyline>, size: Double, tol: Tolerances): Double {
        val d = dtwMean(centerAndScale(aligned(m, user), size), centerAndScale(ref[m.refIndex], size))
        return clamp01(1.0 - d / tol.shape)
    }

    /** Translation- and scale-invariant form; tiny strokes (dots) are not blown up to full size. */
    private fun centerAndScale(line: Polyline, size: Double): Polyline {
        val c = line.centroid()
        val b = BBox.of(line)
        val s = max(hypot(b.width, b.height), MIN_STROKE_SIZE * size)
        return line.map { Point((it.x - c.x) / s, (it.y - c.y) / s) }
    }

    private fun positionScore(m: StrokeMatch, user: List<Polyline>, ref: List<Polyline>, size: Double, tol: Tolerances): Double {
        val u = user[m.userIndex]
        val r = ref[m.refIndex]
        val dc = u.centroid().dist(r.centroid()) / size
        val ub = BBox.of(u)
        val rb = BBox.of(r)
        val e = SIZE_EPS * size
        val sizeErr = max(abs(ln((ub.width + e) / (rb.width + e))), abs(ln((ub.height + e) / (rb.height + e))))
        return 0.6 * clamp01(1.0 - dc / tol.position) + 0.4 * clamp01(1.0 - sizeErr / tol.size)
    }

    private fun isDirectionless(ref: Polyline, size: Double) = ref.length() < DOT_LENGTH * size

    // --- handwriting quality -------------------------------------------------------------------

    private fun beauty(
        reference: CharReference,
        userNorm: List<Polyline>,
        userPolys: List<Polyline>,
        refPolys: List<Polyline>,
        matches: List<StrokeMatch>,
    ): BeautyResult {
        val size = reference.size
        val tol = config.beautyTolerances
        val refCount = refPolys.size.toDouble()
        val shape = matches.sumOf { shapeScore(it, userPolys, refPolys, size, tol) } / refCount
        val position = matches.sumOf { positionScore(it, userPolys, refPolys, size, tol) } / refCount
        val proportions = proportions(reference, userPolys, refPolys, matches, tol)
        val smoothness = smoothness(userNorm, size)
        val score = 100.0 * (0.35 * shape + 0.25 * position + 0.25 * proportions + 0.15 * smoothness)
        return BeautyResult(score, shape, position, proportions, smoothness)
    }

    /** Overall aspect ratio plus placement and size of each top-level component. */
    private fun proportions(
        reference: CharReference,
        userPolys: List<Polyline>,
        refPolys: List<Polyline>,
        matches: List<StrokeMatch>,
        tol: Tolerances,
    ): Double {
        val size = reference.size
        val e = SIZE_EPS * size
        fun boxScore(ub: BBox, rb: BBox): Double {
            val dc = ub.center.dist(rb.center) / size
            val sizeErr = max(abs(ln((ub.width + e) / (rb.width + e))), abs(ln((ub.height + e) / (rb.height + e))))
            return 0.5 * clamp01(1.0 - dc / tol.position) + 0.5 * clamp01(1.0 - sizeErr / tol.size)
        }
        val ub = BBox.of(userPolys.flatten())
        val rb = BBox.of(refPolys.flatten())
        val aspect = clamp01(1.0 - abs(ln((ub.width + e) / (ub.height + e)) - ln((rb.width + e) / (rb.height + e))) / tol.size)

        val parts = reference.topLevelParts()
        if (parts.isEmpty()) return aspect
        val userByRef = matches.associate { it.refIndex to it.userIndex }
        val partScores = parts.map { part ->
            val users = part.strokeIndices.mapNotNull { userByRef[it] }
            if (users.isEmpty()) 0.0
            else boxScore(BBox.of(users.flatMap { userPolys[it] }), BBox.of(part.strokeIndices.flatMap { refPolys[it] }))
        }
        return (aspect + partScores.sum()) / (1 + partScores.size)
    }

    /** Penalizes shaky lines: mean deviation of densely resampled points from their local moving average. */
    private fun smoothness(userNorm: List<Polyline>, size: Double): Double {
        var dev = 0.0
        var count = 0
        for (s in userNorm) {
            val len = s.length()
            if (len < DOT_LENGTH * size) continue
            val pts = s.resample((len / (0.01 * size)).toInt().coerceIn(16, 400))
            val w = 3
            for (i in w until pts.size - w) {
                var sx = 0.0
                var sy = 0.0
                for (k in i - w..i + w) { sx += pts[k].x; sy += pts[k].y }
                val avg = Point(sx / (2 * w + 1), sy / (2 * w + 1))
                dev += pts[i].dist(avg)
                count++
            }
        }
        if (count == 0) return 1.0
        return clamp01(1.0 - (dev / count / size) / SMOOTHNESS_TOLERANCE)
    }

    private companion object {
        /** Strokes are normalized by at least this fraction of the square, so dots keep their absolute size. */
        const val MIN_STROKE_SIZE = 0.12
        /** Additive size epsilon (fraction of the square) so thin strokes' widths don't explode the size ratio. */
        const val SIZE_EPS = 0.08
        /** Strokes shorter than this (fraction of the square) have no meaningful direction. */
        const val DOT_LENGTH = 0.05
        const val SMOOTHNESS_TOLERANCE = 0.01
    }
}
