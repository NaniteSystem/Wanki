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
import app.wanki.scoring.match.StrokeGroup
import app.wanki.scoring.match.StrokeGrouping
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

        // Handwriting style: connected and broken strokes become groups instead of missing/extra strokes.
        val groups = StrokeGrouping.group(userNorm, refRaw, matches, n, size, config.unmatchedCost)
        val gUser = groups.map { g -> StrokeGrouping.concat(g.users.map { userNorm[it] }, n) }
        val gRef = groups.map { g -> StrokeGrouping.concat(g.refs.map { refRaw[it] }, n) }
        val gMatch = groups.indices.map { i ->
            val fwd = StrokeGrouping.meanDist(gUser[i], gRef[i])
            val rev = StrokeGrouping.meanDist(gUser[i].asReversed(), gRef[i])
            StrokeMatch(i, i, rev < fwd, min(fwd, rev) / size)
        }

        val refCount = refPolys.size.toDouble()
        val byWriting = groups.indices.sortedBy { groups[it].users.min() }
        val inOrderGroups = longestIncreasingSubsequence(byWriting.map { groups[it].refs.min() }).map { byWriting[it] }.toSet()

        val tol = config.tolerances
        val pairs = gMatch.map { m -> PairScores(m, shapeScore(m, gUser, gRef, size, tol), positionScore(m, gUser, gRef, size, tol)) }
        fun weight(i: Int) = groups[i].refs.size
        fun groupReversed(i: Int) = gMatch[i].reversed && !(groups[i].refs.size == 1 && isDirectionless(refRaw[groups[i].refs[0]], size))
        val coveredRefs = groups.sumOf { it.refs.size }
        val usedUsers = groups.sumOf { it.users.size }
        val extra = userPolys.size - usedUsers
        val missing = refPolys.size - coveredRefs

        val metrics = MetricScores(
            shape = pairs.indices.sumOf { pairs[it].shape * weight(it) } / refCount,
            position = pairs.indices.sumOf { pairs[it].position * weight(it) } / refCount,
            count = clamp01(1.0 - (missing + extra) / refCount),
            order = inOrderGroups.sumOf { weight(it) } / refCount,
            direction = groups.indices.filter { !groupReversed(it) }.sumOf { weight(it) } / refCount,
        )
        val w = config.weights
        val orderW = w.order * config.orderStrictness.coerceIn(0.0, 1.0)
        val totalW = w.shape + w.position + w.count + orderW + w.direction
        val raw = 100.0 * (w.shape * metrics.shape + w.position * metrics.position + w.count * metrics.count +
            orderW * metrics.order + w.direction * metrics.direction) / totalW

        // The recognizer's first choice being exactly this character means it is legible as written,
        // even when the form differs from the reference: accept it.
        val acceptedByRecognizer = recognitionCandidates?.firstOrNull() == reference.char
        val confusedWith = if (acceptedByRecognizer) null else competitors.filter { it.char != reference.char }.takeIf { it.isNotEmpty() }?.let { cs ->
            val own = fitDistance(reference, strokes)
            cs.map { it to fitDistance(it, strokes) }
                .filter { (_, d) -> d < own * (1 - config.competitorMargin) }
                .minByOrNull { (_, d) -> d }
                ?.first?.char
        }
        val failReasons = if (acceptedByRecognizer) emptySet() else buildSet {
            if (missing > 0 && config.failOnMissingStroke) add(FailReason.MISSING_STROKES)
            if (extra > 0 && config.failOnExtraStroke) add(FailReason.EXTRA_STROKES)
            if (recognition == RecognitionOutcome.NOT_RECOGNIZED) add(FailReason.NOT_RECOGNIZED)
            if (confusedWith != null) add(FailReason.CONFUSED_WITH_LOOKALIKE)
        }
        val score = when {
            acceptedByRecognizer -> max(raw, config.passThreshold)
            failReasons.isNotEmpty() -> min(raw, config.passThreshold - 1)
            else -> raw
        }
        val passed = score >= config.passThreshold

        val feedback = ArrayList<UserStrokeFeedback>()
        val groupOfUser = HashMap<Int, Int>()
        groups.forEachIndexed { gi, g -> g.users.forEach { groupOfUser[it] = gi } }
        for (k in userPolys.indices) {
            val gi = groupOfUser[k]
            feedback += if (gi == null) {
                UserStrokeFeedback(userIndices[k], null, StrokeStatus.EXTRA)
            } else {
                val wrongOrder = gi !in inOrderGroups && config.orderStrictness > 0
                val reversed = groupReversed(gi)
                UserStrokeFeedback(
                    userIndices[k], groups[gi].refs.first(),
                    if (wrongOrder || reversed) StrokeStatus.ORDER_OR_DIRECTION else StrokeStatus.CORRECT,
                    wrongOrder, reversed, pairs[gi].shape, pairs[gi].position,
                    refIndices = groups[gi].refs,
                )
            }
        }
        val matchedRefs = groups.flatMap { it.refs }.toSet()

        val beauty = if (passed) beauty(reference, userNorm, gUser, gRef, gMatch, groups) else null
        return ScoreResult(
            char = reference.char,
            score = score,
            rawScore = raw,
            passed = passed,
            metrics = metrics,
            recognition = recognition,
            confusedWith = confusedWith,
            failReasons = if (passed) emptySet() else failReasons + FailReason.LOW_SCORE.takeIf { raw < config.passThreshold }.let(::setOfNotNull),
            beauty = beauty,
            strokes = feedback,
            missingRefStrokes = refPolys.indices.filter { it !in matchedRefs },
            toReference = transform,
            needsHandwritingPractice = beauty != null && beauty.score < config.beautyPracticeThreshold,
            acceptedByRecognizer = acceptedByRecognizer,
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
        gUser: List<Polyline>,
        gRef: List<Polyline>,
        gMatch: List<StrokeMatch>,
        groups: List<StrokeGroup>,
    ): BeautyResult {
        val size = reference.size
        val tol = config.beautyTolerances
        val refCount = reference.strokes.size.toDouble()
        val shape = gMatch.sumOf { shapeScore(it, gUser, gRef, size, tol) * groups[it.userIndex].refs.size } / refCount
        val position = gMatch.sumOf { positionScore(it, gUser, gRef, size, tol) * groups[it.userIndex].refs.size } / refCount
        val proportions = proportions(reference, gUser, groups, tol)
        val smoothness = smoothness(userNorm, size)
        val score = 100.0 * (0.35 * shape + 0.25 * position + 0.25 * proportions + 0.15 * smoothness)
        return BeautyResult(score, shape, position, proportions, smoothness)
    }

    /** Overall aspect ratio plus placement and size of each top-level component. */
    private fun proportions(
        reference: CharReference,
        gUser: List<Polyline>,
        groups: List<StrokeGroup>,
        tol: Tolerances,
    ): Double {
        val size = reference.size
        val e = SIZE_EPS * size
        val refPolys = reference.strokes.map { it.points }
        fun boxScore(ub: BBox, rb: BBox): Double {
            val dc = ub.center.dist(rb.center) / size
            val sizeErr = max(abs(ln((ub.width + e) / (rb.width + e))), abs(ln((ub.height + e) / (rb.height + e))))
            return 0.5 * clamp01(1.0 - dc / tol.position) + 0.5 * clamp01(1.0 - sizeErr / tol.size)
        }
        val ub = BBox.of(gUser.flatten())
        val rb = BBox.of(refPolys.flatten())
        val aspect = clamp01(1.0 - abs(ln((ub.width + e) / (ub.height + e)) - ln((rb.width + e) / (rb.height + e))) / tol.size)

        val parts = reference.topLevelParts()
        if (parts.isEmpty()) return aspect
        val groupOfRef = HashMap<Int, Int>()
        groups.forEachIndexed { gi, g -> g.refs.forEach { groupOfRef[it] = gi } }
        val partScores = parts.map { part ->
            val gs = part.strokeIndices.mapNotNull { groupOfRef[it] }.toSet()
            if (gs.isEmpty()) 0.0
            else boxScore(BBox.of(gs.flatMap { gUser[it] }), BBox.of(part.strokeIndices.flatMap { refPolys[it] }))
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
