package app.wanki.recognition

import app.wanki.ink.InkDrawing
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.tasks.await

/** ML Kit Digital Ink wrapper. Returns null (gate skipped) whenever recognition is unavailable. */
class HandwritingRecognizer(languageTag: String) : AutoCloseable {
    private val model = DigitalInkRecognitionModel.builder(
        checkNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)) { "No ML Kit model for $languageTag" },
    ).build()
    private val manager = RemoteModelManager.getInstance()
    private val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())

    suspend fun isModelDownloaded(): Boolean = manager.isModelDownloaded(model).await()

    suspend fun downloadModel() {
        manager.download(model, DownloadConditions.Builder().build()).await()
    }

    /** Ranked candidates, or null if the model is not available yet. */
    suspend fun recognize(drawing: InkDrawing, areaWidth: Float, areaHeight: Float): List<String>? {
        if (drawing.strokes.isEmpty() || !isModelDownloaded()) return null
        val ink = Ink.builder()
        for (s in drawing.strokes) {
            val stroke = Ink.Stroke.builder()
            for (p in s.points) stroke.addPoint(Ink.Point.create(p.x, p.y, p.timeMs))
            ink.addStroke(stroke.build())
        }
        val context = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(areaWidth, areaHeight))
            .build()
        return recognizer.recognize(ink.build(), context).await().candidates.map { it.text }
    }

    override fun close() = recognizer.close()
}
