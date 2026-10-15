package app.wanki.srs

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** Ratings use FSRS numbering: Again = 1 … Easy = 4. */
enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class CardState { LEARNING, REVIEW, RELEARNING }

/**
 * Scheduling state of one card. A new card is LEARNING at step 0 with no stability yet.
 * Times are epoch milliseconds.
 */
data class SrsState(
    val state: CardState = CardState.LEARNING,
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long = 0,
    val lastReview: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = lastReview == null
}

/** One review as stored in ReviewLog: the only input needed to rebuild [SrsState]. */
data class Review(val rating: Rating, val timeMs: Long)

/**
 * FSRS-6 scheduler, a port of py-fsrs 6.x (https://github.com/open-spaced-repetition/py-fsrs, MIT).
 *
 * Differences from py-fsrs: fuzz is deterministic (seeded by card id and review number) so that
 * replaying the review log on any device yields exactly the same schedule.
 */
class FsrsScheduler(
    val parameters: DoubleArray = DEFAULT_PARAMETERS,
    val desiredRetention: Double = 0.9,
    val learningStepsMs: LongArray = longArrayOf(MINUTE, 10 * MINUTE),
    val relearningStepsMs: LongArray = longArrayOf(10 * MINUTE),
    val maximumIntervalDays: Int = 36500,
    val enableFuzzing: Boolean = true,
) {
    init {
        require(parameters.size == 21) { "FSRS-6 needs 21 parameters, got ${parameters.size}" }
    }

    private val w = parameters
    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1

    fun retrievability(s: SrsState, nowMs: Long): Double {
        val last = s.lastReview ?: return 0.0
        val stability = s.stability ?: return 0.0
        val elapsedDays = max(0L, floorDays(nowMs - last))
        return (1 + factor * elapsedDays / stability).pow(decay)
    }

    /** Applies one review. [cardSeed] only feeds the fuzz. */
    fun review(s: SrsState, rating: Rating, nowMs: Long, cardSeed: Long = 0): SrsState {
        val daysSince = s.lastReview?.let { floorDays(nowMs - it) }
        var stability = s.stability
        var difficulty = s.difficulty
        var state = s.state
        var step = s.step
        val intervalMs: Long

        fun updateMemory(alwaysUpdateDifficulty: Boolean) {
            if (stability == null || difficulty == null) {
                stability = initialStability(rating)
                difficulty = clampD(initialDifficulty(rating))
                return
            }
            stability = if (daysSince != null && daysSince < 1) {
                shortTermStability(stability!!, rating)
            } else {
                nextStability(difficulty!!, stability!!, retrievability(s, nowMs), rating)
            }
            if (alwaysUpdateDifficulty) difficulty = nextDifficulty(difficulty!!, rating)
        }

        fun toReview(): Long {
            state = CardState.REVIEW
            step = null
            return nextIntervalDays(stability!!) * DAY
        }

        fun stepsFlow(steps: LongArray): Long {
            val st = step ?: 0
            if (steps.isEmpty() || (st >= steps.size && rating != Rating.AGAIN)) return toReview()
            return when (rating) {
                Rating.AGAIN -> { step = 0; steps[0] }
                Rating.HARD -> when {
                    st == 0 && steps.size == 1 -> (steps[0] * 1.5).roundToLong()
                    st == 0 -> (steps[0] + steps[1]) / 2
                    else -> steps[st]
                }
                Rating.GOOD -> if (st + 1 == steps.size) toReview() else { step = st + 1; steps[st + 1] }
                Rating.EASY -> toReview()
            }
        }

        when (s.state) {
            CardState.LEARNING -> {
                updateMemory(alwaysUpdateDifficulty = true)
                intervalMs = stepsFlow(learningStepsMs)
            }
            CardState.REVIEW -> {
                updateMemory(alwaysUpdateDifficulty = true)
                intervalMs = if (rating == Rating.AGAIN && relearningStepsMs.isNotEmpty()) {
                    state = CardState.RELEARNING
                    step = 0
                    relearningStepsMs[0]
                } else {
                    nextIntervalDays(stability!!) * DAY
                }
            }
            CardState.RELEARNING -> {
                updateMemory(alwaysUpdateDifficulty = true)
                intervalMs = stepsFlow(relearningStepsMs)
            }
        }

        val finalInterval = if (enableFuzzing && state == CardState.REVIEW) {
            fuzz(intervalMs, Random(cardSeed * 31 + s.reps))
        } else intervalMs

        return SrsState(
            state = state,
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = nowMs + finalInterval,
            lastReview = nowMs,
            reps = s.reps + 1,
            lapses = s.lapses + if (s.state == CardState.REVIEW && rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Rebuilds the state from the full review log (sorted by time). */
    fun replay(reviews: List<Review>, cardSeed: Long = 0): SrsState =
        reviews.sortedBy { it.timeMs }.fold(SrsState()) { acc, r -> review(acc, r.rating, r.timeMs, cardSeed) }

    /** Due time for each rating, for the "answer buttons" of the review screen. */
    fun preview(s: SrsState, nowMs: Long, cardSeed: Long = 0): Map<Rating, Long> =
        Rating.entries.associateWith { review(s, it, nowMs, cardSeed).due - nowMs }

    // --- formulas (py-fsrs scheduler.py) ------------------------------------------------------

    private fun clampD(d: Double) = d.coerceIn(1.0, 10.0)
    private fun clampS(s: Double) = max(s, STABILITY_MIN)

    private fun initialStability(r: Rating) = clampS(w[r.value - 1])
    private fun initialDifficulty(r: Rating) = w[4] - exp(w[5] * (r.value - 1)) + 1

    fun nextIntervalDays(stability: Double): Long {
        val ivl = (stability / factor) * (desiredRetention.pow(1 / decay) - 1)
        return min(max(Math.round(ivl), 1L), maximumIntervalDays.toLong())
    }

    private fun shortTermStability(s: Double, r: Rating): Double {
        var inc = exp(w[17] * (r.value - 3 + w[18])) * s.pow(-w[19])
        if (r != Rating.AGAIN) inc = max(inc, 1.0)
        return clampS(s * inc)
    }

    private fun nextDifficulty(d: Double, r: Rating): Double {
        val delta = -(w[6] * (r.value - 3))
        val damped = d + (10.0 - d) * delta / 9.0
        return clampD(w[7] * initialDifficulty(Rating.EASY) + (1 - w[7]) * damped)
    }

    private fun nextStability(d: Double, s: Double, r: Double, rating: Rating): Double = clampS(
        if (rating == Rating.AGAIN) {
            min(
                w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14]),
                s / exp(w[17] * w[18]),
            )
        } else {
            val hard = if (rating == Rating.HARD) w[15] else 1.0
            val easy = if (rating == Rating.EASY) w[16] else 1.0
            s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hard * easy)
        },
    )

    private fun fuzz(intervalMs: Long, random: Random): Long {
        val days = intervalMs / DAY
        if (days < 2.5) return intervalMs
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) delta += f * max(min(days.toDouble(), end) - start, 0.0)
        var minIvl = Math.round(days - delta)
        val maxIvl = min(Math.round(days + delta), maximumIntervalDays.toLong())
        minIvl = min(max(2L, minIvl), maxIvl)
        val fuzzed = min(Math.round(random.nextDouble() * (maxIvl - minIvl + 1) + minIvl), maximumIntervalDays.toLong())
        return fuzzed * DAY
    }

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val STABILITY_MIN = 0.001

        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )

        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        /** Python's timedelta.days: floor division. */
        private fun floorDays(ms: Long): Long = floor(ms.toDouble() / DAY).toLong()
    }
}
