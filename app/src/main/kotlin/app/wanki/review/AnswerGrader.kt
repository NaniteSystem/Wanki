package app.wanki.review

import app.wanki.data.Settings
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.recognition.RecognizerPool
import app.wanki.refs.ReferenceRepository
import app.wanki.scoring.HandwritingScorer
import app.wanki.scoring.ScoreResult
import app.wanki.scoring.geom.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * How a written answer is laid out:
 * - [Cells]: CJK/kana/hangul, one character per cell (原稿用紙), each graded on its own;
 * - [Line]: anything else (words, sentences, alphabets), recognized as a whole.
 */
sealed interface WritingLayout {
    data class Cells(val chars: List<String>) : WritingLayout
    data object Line : WritingLayout

    companion object {
        fun forAnswer(answer: String): WritingLayout {
            val chars = answer.trim().codePoints().toArray().map { String(Character.toChars(it)) }
            return if (chars.isNotEmpty() && chars.size <= 12 && chars.all { isCellChar(it.codePointAt(0)) }) Cells(chars) else Line
        }

        fun isCellChar(cp: Int) = cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
            cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF || cp == 0x3005
    }
}

data class CellGrade(
    val char: String,
    /** null = cannot tell (no reference and no recognizer) */
    val passed: Boolean?,
    val score: Double?,
    val result: ScoreResult?,
    val candidates: List<String>?,
)

data class Grade(
    /** null = cannot be graded automatically: the user rates themselves. */
    val passed: Boolean?,
    val score: Double?,
    val beauty: Double?,
    val cells: List<CellGrade> = emptyList(),
    val recognized: String? = null,
)

/**
 * Grades a written answer. Characters with a stroke reference go through [HandwritingScorer]
 * (with the recognizer as gate and look-alikes as competitors); others rely on the recognizer.
 */
class AnswerGrader(
    private val references: ReferenceRepository,
    private val recognizers: RecognizerPool,
    private val settings: Settings,
) {
    suspend fun gradeCells(
        chars: List<String>,
        cells: List<List<InkStroke>>,
        cellSize: Float,
        language: String,
    ): Grade {
        val recognizer = recognizers.get(language)
        val scorer = HandwritingScorer(settings.scoringConfig())
        val grades = chars.mapIndexed { i, char ->
            val strokes = cells.getOrElse(i) { emptyList() }
            if (strokes.isEmpty()) return@mapIndexed CellGrade(char, false, 0.0, null, null)
            val candidates = recognizer.recognize(strokes, cellSize, cellSize)
            val ref = references.get(char)
            if (ref != null) {
                val result = withContext(Dispatchers.Default) {
                    scorer.score(ref, strokes.map { s -> s.points.map { Point(it.x.toDouble(), it.y.toDouble()) } },
                        candidates, references.competitorsFor(char, candidates))
                }
                CellGrade(char, result.passed, result.score, result, candidates)
            } else {
                val ok = candidates?.take(3)?.contains(char)
                CellGrade(char, ok, ok?.let { if (it) 100.0 else 0.0 }, null, candidates)
            }
        }
        val passed = when {
            grades.any { it.passed == false } -> false
            grades.all { it.passed == true } -> true
            else -> null
        }
        val scores = grades.mapNotNull { it.score }
        val beauties = grades.mapNotNull { it.result?.beauty?.score }
        return Grade(
            passed = passed,
            score = if (scores.size == grades.size) scores.min() else null,
            beauty = if (beauties.isNotEmpty() && beauties.size == grades.size) beauties.average() else null,
            cells = grades,
        )
    }

    suspend fun gradeLine(answer: String, strokes: List<InkStroke>, width: Float, height: Float, language: String): Grade {
        if (strokes.isEmpty()) return Grade(false, 0.0, null)
        val candidates = recognizers.get(language).recognize(strokes, width, height)
            ?: return Grade(null, null, null)
        val target = normalize(answer)
        val ok = candidates.take(3).any { normalize(it) == target }
        return Grade(ok, if (ok) 100.0 else 0.0, null, recognized = candidates.firstOrNull())
    }

    companion object {
        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase()
            .replace('ё', 'е')
            .filter { it.isLetterOrDigit() }

        /** Splits strokes into [count] equal cells laid out left to right; strokes are made cell-relative. */
        fun splitIntoCells(strokes: List<InkStroke>, count: Int, cellWidth: Float): List<List<InkStroke>> {
            val cells = List(count) { ArrayList<InkStroke>() }
            for (s in strokes) {
                val b = s.bounds() ?: continue
                val cx = (b.left + b.right) / 2
                val i = (cx / cellWidth).toInt().coerceIn(0, count - 1)
                val dx = i * cellWidth
                cells[i] += s.copy(points = s.points.map { p: InkPoint -> p.copy(x = p.x - dx) })
            }
            return cells
        }
    }
}
