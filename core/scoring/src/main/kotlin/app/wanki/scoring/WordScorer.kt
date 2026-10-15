package app.wanki.scoring

import app.wanki.scoring.geom.Polyline

/** One cell of a 原稿用紙-style grid: the attempt for a single character. */
data class CellAttempt(
    val strokes: List<Polyline>,
    val recognitionCandidates: List<String>? = null,
    val competitors: List<CharReference> = emptyList(),
)

data class WordScoreResult(val cells: List<ScoreResult>) {
    /** The word is correct only if every character passes. */
    val passed: Boolean get() = cells.isNotEmpty() && cells.all { it.passed }
    val minScore: Double get() = cells.minOfOrNull { it.score } ?: 0.0
    val meanScore: Double get() = if (cells.isEmpty()) 0.0 else cells.sumOf { it.score } / cells.size
}

class WordScorer(private val scorer: HandwritingScorer = HandwritingScorer()) {
    fun score(references: List<CharReference>, cells: List<CellAttempt>): WordScoreResult {
        val results = references.mapIndexed { i, ref ->
            val cell = cells.getOrNull(i) ?: CellAttempt(emptyList())
            scorer.score(ref, cell.strokes, cell.recognitionCandidates, cell.competitors)
        }
        return WordScoreResult(results)
    }
}
