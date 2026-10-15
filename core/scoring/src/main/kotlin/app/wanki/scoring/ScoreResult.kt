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

enum class FailReason {
    /** A reference stroke was not written (a character missing a stroke is a different character). */
    MISSING_STROKES,
    /** A stroke that is not in the reference was written. */
    EXTRA_STROKES,
    NOT_RECOGNIZED,
    CONFUSED_WITH_LOOKALIKE,
    /** The weighted score itself is below the threshold. */
    LOW_SCORE,
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
    /** All reference strokes this stroke writes (several for connected strokes). */
    val refIndices: List<Int> = listOfNotNull(refIndex),
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
    /** Final score 0..100; any [FailReason] other than LOW_SCORE caps it at threshold − 1. */
    val score: Double,
    /** Weighted score before the hard caps (missing/extra strokes, recognition, look-alikes). */
    val rawScore: Double,
    val passed: Boolean,
    val metrics: MetricScores,
    val recognition: RecognitionOutcome,
    /** A look-alike character the attempt matches better than the target (the score is then capped). */
    val confusedWith: String? = null,
    /** Why the attempt did not pass; empty when [passed]. */
    val failReasons: Set<FailReason> = emptySet(),
    /** Handwriting quality; only computed when [passed]. */
    val beauty: BeautyResult?,
    val strokes: List<UserStrokeFeedback>,
    /** Reference strokes nothing was matched to (red in the review screen). */
    val missingRefStrokes: List<Int>,
    /** Maps user coordinates into the reference square, for drawing the attempt over the reference. */
    val toReference: Similarity?,
    /** Recalled, but handwriting below [ScoringConfig.beautyPracticeThreshold]. */
    val needsHandwritingPractice: Boolean = false,
    /** The recognizer's first choice was this character, so the form was accepted as legible handwriting. */
    val acceptedByRecognizer: Boolean = false,
)
