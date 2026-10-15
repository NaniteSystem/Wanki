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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

enum class ModelStatus { UNKNOWN, DOWNLOADING, READY, UNAVAILABLE }

/**
 * ML Kit Digital Ink for one language. The model is downloaded on first use; until it is there
 * every call returns null and callers fall back (geometry-only scoring, self-rating, no search).
 */
class HandwritingRecognizer(val language: String) : AutoCloseable {
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

    /** Ranked candidates, or null when recognition is unavailable. */
    suspend fun recognize(
        strokes: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        preContext: String = "",
    ): List<String>? {
        val r = recognizer ?: return null
        if (strokes.isEmpty() || !ensureModel()) return null
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
        return runCatching { r.recognize(ink.build(), context).await().candidates.map { it.text } }.getOrNull()
    }

    override fun close() {
        recognizer?.close()
    }

    companion object {
        /** Our deck/notebook language codes → ML Kit language tags. */
        fun mlKitTag(language: String): String = when (language) {
            "zh" -> "zh-Hani-CN"
            "en" -> "en-US"
            else -> language
        }
    }
}

/** One recognizer per language, created lazily and kept for the app's lifetime. */
class RecognizerPool {
    private val map = HashMap<String, HandwritingRecognizer>()

    @Synchronized
    fun get(language: String): HandwritingRecognizer = map.getOrPut(language) { HandwritingRecognizer(language) }
}
