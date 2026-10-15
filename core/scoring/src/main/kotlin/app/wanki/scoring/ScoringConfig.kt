package app.wanki.scoring

/** Metric weights for the correctness score. Relative values; they need not sum to 100. */
data class Weights(
    val shape: Double = 35.0,
    val position: Double = 25.0,
    val count: Double = 15.0,
    val order: Double = 15.0,
    val direction: Double = 10.0,
)

/**
 * Error at which a per-stroke metric reaches zero (linear falloff from a perfect match).
 * All distances are in units of the reference square side, sizes are |ln(ratio)|.
 */
data class Tolerances(
    /** DTW distance between centered, size-normalized strokes, in units of the stroke size. */
    val shape: Double,
    /** Distance between stroke centroids. */
    val position: Double,
    /** |ln(user size / reference size)| for stroke width and height. */
    val size: Double,
) {
    companion object {
        val NORMAL = Tolerances(shape = 0.30, position = 0.15, size = 0.70)
        val STRICT = Tolerances(shape = 0.15, position = 0.07, size = 0.35)
    }
}

data class ScoringConfig(
    /** Score (0–100) from which the character counts as recalled. */
    val passThreshold: Double = 60.0,
    /** Below this handwriting score a character goes to the "practice handwriting" queue. */
    val beautyPracticeThreshold: Double = 75.0,
    val weights: Weights = Weights(),
    /** 0 = stroke order is ignored (e.g. Arabic), 1 = full weight. */
    val orderStrictness: Double = 1.0,
    val tolerances: Tolerances = Tolerances.NORMAL,
    val beautyTolerances: Tolerances = Tolerances.STRICT,
    /** Cost of leaving a stroke unpaired during matching; pairs costing more than twice this are dropped. */
    val unmatchedCost: Double = 0.12,
    /** The recognizer must return the target among its first [recognitionTopK] candidates. */
    val recognitionTopK: Int = 3,
    /** Relative [HandwritingScorer.fitDistance] advantage a look-alike needs to count as "a different character". */
    val competitorMargin: Double = 0.0,
    /** A missing stroke makes it a different character: cap below the threshold. */
    val failOnMissingStroke: Boolean = true,
    /** Same for a stroke that is not in the reference (木 + stroke = 本, 王 + dot = 玉). */
    val failOnExtraStroke: Boolean = true,
    val resamplePoints: Int = 32,
)
