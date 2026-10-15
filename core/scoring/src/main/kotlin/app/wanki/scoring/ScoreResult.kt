package app.wanki.scoring

import app.wanki.scoring.geom.Similarity

/** Per-metric results, each 0..1 and already weighted by coverage of the reference strokes. */
data class MetricScores(
    val shape: Double,
    val position: Double,
    val count: Double,
    val order: Double,
    val direction: Double,
)

enum class RecognitionOutcome {
    /** No recognizer result was supplied (offline, model not downloaded…). The gate is skipped. */
    NOT_CHECKED,
    IN_TOP_K,
    /** The recognizer did not see the target character: the score is capped below the threshold. */
    NOT_RECOGNIZED,
}

/** Colour category for the attempt review screen. */
enum class StrokeStatus {
    /** green */
    CORRECT,
    /** yellow: right stroke, but wrong order and/or direction */
    ORDER_OR_DIRECTION,
    /** red: does not correspond to any reference stroke */
    EXTRA,
}

data class UserStrokeFeedback(
    /** Index into the strokes passed to the scorer. */
    val userIndex: Int,
    val refIndex: Int?,
    val status: StrokeStatus,
    val wrongOrder: Boolean = false,
    val reversed: Boolean = false,
    val shape: Double = 0.0,
    val position: Double = 0.0,
)

data class BeautyResult(
    /** 0..100 */
    val score: Double,
    val shape: Double,
    val position: Double,
    val proportions: Double,
    val smoothness: Double,
)

data class ScoreResult(
    val char: String,
    /** Final score 0..100, after the recognition gate. */
    val score: Double,
    /** Score before the recognition gate. */
    val rawScore: Double,
    val passed: Boolean,
    val metrics: MetricScores,
    val recognition: RecognitionOutcome,
    /** A look-alike character the attempt matches better than the target (the score is then capped). */
    val confusedWith: String? = null,
    /** Handwriting quality; only computed when [passed]. */
    val beauty: BeautyResult?,
    val strokes: List<UserStrokeFeedback>,
    /** Reference strokes nothing was matched to (red in the review screen). */
    val missingRefStrokes: List<Int>,
    /** Maps user coordinates into the reference square, for drawing the attempt over the reference. */
    val toReference: Similarity?,
    /** Recalled, but handwriting below [ScoringConfig.beautyPracticeThreshold]. */
    val needsHandwritingPractice: Boolean = false,
)
