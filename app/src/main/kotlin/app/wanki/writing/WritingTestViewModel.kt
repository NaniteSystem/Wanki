package app.wanki.writing

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.wanki.ink.FingerMode
import app.wanki.ink.InkCanvasState
import app.wanki.ink.InkDrawing
import app.wanki.recognition.HandwritingRecognizer
import app.wanki.refs.ReferenceRepository
import app.wanki.scoring.CharReference
import app.wanki.scoring.HandwritingScorer
import app.wanki.scoring.Rating
import app.wanki.scoring.RatingPolicy
import app.wanki.scoring.ScoreResult
import app.wanki.scoring.geom.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecognizerStatus { CHECKING, DOWNLOADING, READY, UNAVAILABLE }

data class Attempt(
    val result: ScoreResult,
    val rating: Rating,
    val candidates: List<String>?,
    val durationMs: Long,
    val usedHint: Boolean,
)

/** Test bench for the scoring pipeline: write a character, see the score and the review. */
class WritingTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ReferenceRepository(app.assets)
    private val scorer = HandwritingScorer()
    private val ratingPolicy = RatingPolicy()
    private val recognizer = runCatching { HandwritingRecognizer("ja") }.getOrNull()

    val chars: List<String> = repo.chars
    val canvas = InkCanvasState()

    var index by mutableStateOf(chars.indexOf("右").coerceAtLeast(0))
        private set
    var showGhost by mutableStateOf(false)
        private set
    var hideTarget by mutableStateOf(false)
    var fingerMode by mutableStateOf(FingerMode.AUTO)
    var attempt by mutableStateOf<Attempt?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var recognizerStatus by mutableStateOf(RecognizerStatus.CHECKING)
        private set

    /** Turning the ghost on at any point of an attempt counts as a hint (→ Hard). */
    private var hintUsed = false

    val char: String get() = chars.getOrElse(index) { "" }
    val reference: CharReference? get() = repo.get(char)

    init {
        viewModelScope.launch {
            val r = recognizer ?: run { recognizerStatus = RecognizerStatus.UNAVAILABLE; return@launch }
            recognizerStatus = runCatching {
                if (!r.isModelDownloaded()) {
                    recognizerStatus = RecognizerStatus.DOWNLOADING
                    r.downloadModel()
                }
                RecognizerStatus.READY
            }.getOrDefault(RecognizerStatus.UNAVAILABLE)
        }
    }

    fun select(i: Int) {
        if (chars.isEmpty()) return
        index = Math.floorMod(i, chars.size)
        reset()
    }

    fun next() = select(index + 1)
    fun previous() = select(index - 1)

    fun toggleGhost() {
        showGhost = !showGhost
        if (showGhost && attempt == null) hintUsed = true
    }

    fun reset() {
        canvas.clear()
        attempt = null
        hintUsed = showGhost
    }

    fun check(canvasWidth: Float, canvasHeight: Float) {
        val ref = reference ?: return
        val drawing: InkDrawing = canvas.drawing()
        if (drawing.strokes.isEmpty() || checking) return
        checking = true
        viewModelScope.launch {
            val candidates = runCatching { recognizer?.recognize(drawing, canvasWidth, canvasHeight) }.getOrNull()
            val usedHint = hintUsed
            val result = withContext(Dispatchers.Default) {
                val competitors = repo.competitorsFor(ref.char, candidates)
                val strokes = drawing.strokes.map { s -> s.points.map { Point(it.x.toDouble(), it.y.toDouble()) } }
                scorer.score(ref, strokes, candidates, competitors)
            }
            val rating = ratingPolicy.suggest(result, usedHint, drawing.durationMs, ref.strokes.size)
            attempt = Attempt(result, rating, candidates, drawing.durationMs, usedHint)
            checking = false
        }
    }

    override fun onCleared() {
        recognizer?.close()
    }
}
