package app.wanki.texts

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Japanese morphology backed by Kuromoji (ipadic); readings are katakana, "*" (unknown, symbols) becomes null. */
class KuromojiMorphology : Morphology {
    // loading the dictionary takes a while: built once, on first use, safe from any thread
    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    /** Builds the tokenizer off the main thread so the first [tokens] call is fast. */
    suspend fun warmUp() = withContext(Dispatchers.Default) { tokenizer; Unit }

    override fun tokens(text: String): List<MorphToken> =
        tokenizer.tokenize(text).map {
            val start = it.position
            MorphToken(start, start + it.surface.length, it.reading?.takeIf { r -> r != "*" })
        }
}
