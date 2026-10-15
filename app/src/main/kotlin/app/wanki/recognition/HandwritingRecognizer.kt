package app.wanki.recognition

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wanki.ink.InkStroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

enum class ModelStatus { UNKNOWN, DOWNLOADING, READY, UNAVAILABLE }

/**
 * ML Kit Digital Ink for one language. The model is downloaded on first use; until it is there
 * every call returns null and callers fall back (geometry-only scoring, self-rating, no search).
 */
class HandwritingRecognizer(val language: String, private val scope: CoroutineScope) : AutoCloseable {
    private val model: DigitalInkRecognitionModel? = runCatching {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(mlKitTag(language))
            ?.let { DigitalInkRecognitionModel.builder(it).build() }
    }.getOrNull()
    private val recognizer: DigitalInkRecognizer? =
        model?.let { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(it).build()) }
    private val manager = RemoteModelManager.getInstance()
    private val downloadLock = Mutex()

    var status by mutableStateOf(if (model == null) ModelStatus.UNAVAILABLE else ModelStatus.UNKNOWN)
        private set

    /** Makes sure the model is on the device; returns whether it is usable. */
    suspend fun ensureModel(): Boolean {
        val m = model ?: return false
        if (status == ModelStatus.READY) return true
        return downloadLock.withLock {
            runCatching {
                if (!manager.isModelDownloaded(m).await()) {
                    status = ModelStatus.DOWNLOADING
                    manager.download(m, DownloadConditions.Builder().build()).await()
                }
                status = ModelStatus.READY
                true
            }.getOrElse {
                status = ModelStatus.UNAVAILABLE
                false
            }
        }
    }

    /** Starts the model download in the background if needed; never blocks. */
    fun prefetch() {
        if (model != null && status != ModelStatus.READY) scope.launch { ensureModel() }
    }

    /** Whether the model can be used right now; if not, the download is started in the background. */
    private suspend fun readyNow(): Boolean {
        if (status == ModelStatus.READY) return true
        // ML Kit queues calls behind a running download, so don't even ask while one is going on.
        if (status == ModelStatus.DOWNLOADING || downloadLock.isLocked) return false
        val m = model ?: return false
        val downloaded = withTimeoutOrNull(QUICK_TIMEOUT_MS) {
            runCatching { manager.isModelDownloaded(m).await() }.getOrDefault(false)
        } ?: false
        if (downloaded) {
            status = ModelStatus.READY
            return true
        }
        prefetch()
        return false
    }

    /**
     * Ranked candidates, or null when recognition is unavailable. Answer checking must not wait
     * for a first-time model download, so by default a missing model just yields null
     * ([waitForModel] = true is for background work such as page recognition).
     */
    suspend fun recognize(
        strokes: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        preContext: String = "",
        waitForModel: Boolean = false,
    ): List<String>? {
        val r = recognizer ?: return null
        if (strokes.isEmpty()) return null
        if (!(if (waitForModel) ensureModel() else readyNow())) return null
        val ink = Ink.builder()
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            val stroke = Ink.Stroke.builder()
            for (p in s.points) stroke.addPoint(Ink.Point.create(p.x, p.y, p.timeMs))
            ink.addStroke(stroke.build())
        }
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(areaWidth.coerceAtLeast(1f), areaHeight.coerceAtLeast(1f)))
            .build()
        val timeout = if (waitForModel) LONG_TIMEOUT_MS else QUICK_TIMEOUT_MS * 2
        return withTimeoutOrNull(timeout) {
            runCatching { r.recognize(ink.build(), context).await().candidates.map { it.text } }.getOrNull()
        }
    }

    override fun close() {
        recognizer?.close()
    }

    companion object {
        /** Answer checking never waits longer than this for the recognizer. */
        private const val QUICK_TIMEOUT_MS = 1_500L
        private const val LONG_TIMEOUT_MS = 30_000L

        /** Our deck/notebook language codes → ML Kit language tags. */
        fun mlKitTag(language: String): String = when (language) {
            "zh" -> "zh-Hani-CN"
            "en" -> "en-US"
            else -> language
        }
    }
}

/** One recognizer per language, created lazily and kept for the app's lifetime. */
class RecognizerPool(private val scope: CoroutineScope) {
    private val map = HashMap<String, HandwritingRecognizer>()

    @Synchronized
    fun get(language: String): HandwritingRecognizer = map.getOrPut(language) { HandwritingRecognizer(language, scope) }
}
