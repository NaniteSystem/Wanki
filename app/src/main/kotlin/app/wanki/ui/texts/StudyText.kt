package app.wanki.ui.texts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.wanki.data.TextPieceEntity
import app.wanki.texts.FirstLetters
import app.wanki.texts.VanishingCues
import app.wanki.texts.Word
import app.wanki.texts.WordDisplay

/** A span of a piece: a breaker [Word] with its index, or the punctuation/space gap between words. */
private sealed interface Token {
    data class Wd(val index: Int, val text: String) : Token
    data class Gap(val text: String) : Token
}

private fun tokenize(content: String, words: List<Word>): List<Token> {
    val out = ArrayList<Token>()
    var cursor = 0
    words.forEachIndexed { i, w ->
        if (w.start > cursor) out.add(Token.Gap(content.substring(cursor, w.start)))
        out.add(Token.Wd(i, content.substring(w.start, w.end)))
        cursor = w.end
    }
    if (cursor < content.length) out.add(Token.Gap(content.substring(cursor)))
    return out
}

/**
 * Renders one piece word by word. A word is shown when it is [WordDisplay.VISIBLE], when [allRevealed]
 * ("Показать") or when the reader tapped it ([revealed]); otherwise it is a blank of the word's length
 * (first-letter level shows the hint). Tapping a hidden word calls [onReveal] and counts as a hint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PieceWords(
    piece: TextPieceEntity,
    language: String,
    words: List<Word>,
    display: List<WordDisplay>,
    revealed: Set<Int>,
    allRevealed: Boolean,
    style: TextStyle,
    onReveal: (Int) -> Unit,
) {
    Directional(language) {
        FlowRow(verticalArrangement = Arrangement.Center) {
            tokenize(piece.content, words).forEach { token ->
                when (token) {
                    is Token.Gap -> Text(token.text, style = style)
                    is Token.Wd -> {
                        val d = display.getOrElse(token.index) { WordDisplay.VISIBLE }
                        val shown = allRevealed || d == WordDisplay.VISIBLE || token.index in revealed
                        when {
                            shown -> Text(
                                token.text,
                                style = style,
                                color = if (d == WordDisplay.VISIBLE) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.tertiary,
                            )
                            d == WordDisplay.FIRST_LETTER -> Blank(FirstLetters.hint(token.text), token.text.length, style) { onReveal(token.index) }
                            else -> Blank(null, token.text.length, style) { onReveal(token.index) }
                        }
                    }
                }
            }
        }
    }
}

/** A tappable underlined gap standing for a hidden word; [hint] shows the first letter at the first-letter level. */
@Composable
private fun Blank(hint: String?, length: Int, style: TextStyle, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
            .width((length.coerceIn(2, 18) * 11).dp)
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .drawBehind { drawLine(line, Offset(2f, size.height - 3f), Offset(size.width - 2f, size.height - 3f), 3f) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (hint != null) Text(hint, style = style, color = line)
    }
}

/** The cue display for a piece at [level], or all-hidden when [hidden] (recital/chain targets). */
fun cueDisplay(piece: TextPieceEntity, wordCount: Int, level: Int, hidden: Boolean): List<WordDisplay> =
    if (hidden) List(wordCount) { WordDisplay.HIDDEN } else VanishingCues.display(level, wordCount, piece.seed)
