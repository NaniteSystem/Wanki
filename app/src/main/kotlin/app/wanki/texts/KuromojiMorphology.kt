package app.wanki.texts

import com.atilika.kuromoji.ipadic.Tokenizer

/** Japanese morphology backed by Kuromoji (ipadic); readings are katakana, "*" (unknown, symbols) becomes null. */
class KuromojiMorphology : Morphology {
    // loading the dictionary takes a while: built once, on first use, safe from any thread
    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    override fun tokens(text: String): List<MorphToken> =
        tokenizer.tokenize(text).map {
            val start = it.position
            MorphToken(start, start + it.surface.length, it.reading?.takeIf { r -> r != "*" })
        }
}
