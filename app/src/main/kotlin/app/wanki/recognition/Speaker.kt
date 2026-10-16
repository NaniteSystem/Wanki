package app.wanki.recognition

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads answers aloud with the system text-to-speech engine. The engine starts on first use;
 * [speak] returns false when there is no voice for the language (or no engine at all).
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun speak(text: String, language: String): Boolean {
        if (text.isBlank()) return true
        val engine = tts
        if (engine == null) {
            pending = text to language
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    ready = status == TextToSpeech.SUCCESS
                    pending?.let { (t, l) -> if (ready) say(t, l) }
                    pending = null
                }
            }
            return true
        }
        if (!ready) {
            pending = text to language
            return true
        }
        return say(text, language)
    }

    private fun say(text: String, language: String): Boolean {
        val engine = tts ?: return false
        val result = engine.setLanguage(locale(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wanki")
        return true
    }

    /** Whether the engine reported a voice for the language (false until it has started). */
    fun hasVoice(language: String): Boolean {
        val engine = tts ?: return false
        if (!ready) return false
        val r = engine.isLanguageAvailable(locale(language))
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    companion object {
        fun locale(language: String): Locale = when (language) {
            "ja" -> Locale.JAPAN
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "ko" -> Locale.KOREA
            "en" -> Locale.US
            else -> Locale.forLanguageTag(language)
        }
    }
}
