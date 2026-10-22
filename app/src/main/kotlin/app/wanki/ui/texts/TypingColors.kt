package app.wanki.ui.texts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.wanki.isDarkTheme

/**
 * Colours of the «Печать» mode (after the Speech Practice page): typed right in plain ink, wrong red, skipped
 * orange, extra red and struck through, IME composition blue, the sample faint, hints and marks in the accent.
 */
@Immutable
data class TypingColors(
    val ok: Color,
    val wrong: Color,
    val skipped: Color,
    val extra: Color,
    val composing: Color,
    val faint: Color,
    val accent: Color,
)

val LightTypingColors = TypingColors(
    ok = Color(0xFF1D1D1F),
    wrong = Color(0xFFD32F2F),
    skipped = Color(0xFFC25E00),
    extra = Color(0xFFD32F2F),
    composing = Color(0xFF1565C0),
    faint = Color(0xFFB4B0A6),
    accent = Color(0xFF2F5D8A),
)

val DarkTypingColors = TypingColors(
    ok = Color(0xFFECECEE),
    wrong = Color(0xFFFF6B6B),
    skipped = Color(0xFFFBBF24),
    extra = Color(0xFFFF6B6B),
    composing = Color(0xFF64B5F6),
    faint = Color(0xFF5C5C64),
    accent = Color(0xFF7FB0E0),
)

@Composable
fun typingColors(): TypingColors = if (isDarkTheme()) DarkTypingColors else LightTypingColors
