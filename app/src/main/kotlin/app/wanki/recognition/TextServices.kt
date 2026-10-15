package app.wanki.recognition

import app.wanki.data.Settings
import app.wanki.ink.BrushKind
import app.wanki.ink.InkStroke
import app.wanki.ink.LineSegmenter
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** "auto" = try the user's languages and keep the most plausible result. */
const val AUTO_LANGUAGE = "auto"

data class RecognizedText(val text: String, val language: String?)

/**
 * Handwriting → text for any language. With a fixed language one model is used; with
 * [AUTO_LANGUAGE] each line is recognized by every candidate model and the result whose text the
 * language identifier finds most convincing in that model's language wins.
 */
class TextRecognition(private val pool: RecognizerPool, private val settings: Settings) {
    private val languageId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build(),
    )

    fun candidateLanguages(language: String): List<String> =
        if (language == AUTO_LANGUAGE) settings.autoLanguageList.ifEmpty { listOf("en") } else listOf(language)

    /** Recognizes strokes as lines of text. Highlighter strokes are ignored. Null if no model is usable yet. */
    suspend fun recognize(strokes: List<InkStroke>, language: String, wait: Boolean = true): RecognizedText? {
        val writing = strokes.filter { it.brush.kind != BrushKind.HIGHLIGHTER && it.points.isNotEmpty() }
        if (writing.isEmpty()) return RecognizedText("", null)
        val langs = candidateLanguages(language)
        val out = StringBuilder()
        val votes = HashMap<String, Int>()
        for (line in LineSegmenter.segment(writing)) {
            val lineStrokes = line.map { writing[it] }
            val boxes = lineStrokes.mapNotNull { it.bounds() }
            val width = boxes.maxOf { it.right } - boxes.minOf { it.left }
            val height = boxes.maxOf { it.bottom } - boxes.minOf { it.top }
            val (text, lang) = recognizeLine(lineStrokes, width * 1.2f, height * 1.4f, out.toString(), langs, wait) ?: return null
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
            if (lang != null) votes.merge(lang, text.length, Int::plus)
        }
        return RecognizedText(out.toString(), votes.maxByOrNull { it.value }?.key)
    }

    private suspend fun recognizeLine(
        strokes: List<InkStroke>, w: Float, h: Float, pre: String, langs: List<String>, wait: Boolean,
    ): Pair<String, String?>? {
        val results = langs.mapNotNull { lang ->
            pool.get(lang).recognize(strokes, w, h, pre, waitForModel = wait)?.firstOrNull()?.let { lang to it }
        }
        if (results.isEmpty()) return null
        if (results.size == 1) return results[0].second to results[0].first
        val best = results.maxByOrNull { (lang, text) -> plausibility(text, lang) }!!
        return best.second to best.first
    }

    /** How convincing [text] is as [lang]: language-identification confidence, with a script check for CJK. */
    private suspend fun plausibility(text: String, lang: String): Double {
        if (text.isBlank()) return 0.0
        val script = scriptMatch(text, lang)
        val id = runCatching { languageId.identifyPossibleLanguages(text).await() }.getOrNull().orEmpty()
        val conf = id.firstOrNull { it.languageTag.substringBefore('-') == lang }?.confidence?.toDouble() ?: 0.0
        return conf + script
    }

    private fun scriptMatch(text: String, lang: String): Double {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        fun share(p: (Char) -> Boolean) = letters.count(p).toDouble() / letters.length
        val cyr = share { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        val kana = share { it in '぀'..'ヿ' }
        val han = share { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val hangul = share { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
        val arabic = share { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }
        val latin = share { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        return when (lang) {
            "ru" -> cyr
            "ja" -> kana + han * 0.6
            "zh" -> han - kana
            "ko" -> hangul
            "ar" -> arabic
            else -> latin
        } * 0.5
    }

    suspend fun identify(text: String): String? =
        runCatching { languageId.identifyLanguage(text).await() }.getOrNull()?.takeIf { it != "und" }?.substringBefore('-')
}

/** On-device translation (ML Kit Translate); models are downloaded on first use of a language pair. */
class TranslationService(private val textRecognition: TextRecognition) {
    private val translators = HashMap<Pair<String, String>, Translator>()

    sealed interface Result {
        data class Ok(val text: String, val from: String, val to: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun translate(text: String, to: String, from: String? = null): Result {
        if (text.isBlank()) return Result.Failed("нет текста")
        val source = from ?: textRecognition.identify(text) ?: return Result.Failed("не удалось определить язык")
        if (source == to) return Result.Ok(text, source, to)
        val src = TranslateLanguage.fromLanguageTag(source) ?: return Result.Failed("язык «$source» не поддерживается переводчиком")
        val dst = TranslateLanguage.fromLanguageTag(to) ?: return Result.Failed("язык «$to» не поддерживается переводчиком")
        val translator = synchronized(translators) {
            translators.getOrPut(src to dst) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
            }
        }
        return runCatching {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Result.Ok(translator.translate(text).await(), source, to)
        }.getOrElse { Result.Failed("перевод недоступен: ${it.message ?: "нет модели или сети"}") }
    }
}
