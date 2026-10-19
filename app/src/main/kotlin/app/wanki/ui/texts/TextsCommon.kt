package app.wanki.ui.texts

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wanki.texts.TextKind
import app.wanki.texts.TextSchedule
import app.wanki.texts.Words
import app.wanki.ui.common.Languages
import java.util.Locale

internal fun scheduleName(schedule: TextSchedule) = if (schedule == TextSchedule.HIFZ) "Сабак·сабки·манзиль" else "FSRS"
internal fun scheduleName(schedule: String) = scheduleName(scheduleOf(schedule))
internal fun scheduleOf(name: String) = TextSchedule.entries.firstOrNull { it.name == name } ?: TextSchedule.FSRS
internal fun kindOf(name: String) = TextKind.entries.firstOrNull { it.name == name } ?: TextKind.POEM
internal fun kindName(kind: String) = if (kindOf(kind) == TextKind.PROSE) "проза" else "стих"

/** "3 куска". */
internal fun piecesCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "кусков"
        n % 10 == 1 -> "кусок"
        n % 10 in 2..4 -> "куска"
        else -> "кусков"
    }
    return "$n $word"
}

/** Any language code ML Kit may report, not only the ones offered as chips. */
internal fun textLanguageName(code: String): String =
    Languages.firstOrNull { it.first == code }?.second
        ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("ru")).ifBlank { code }
            .replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }

/** Piece text: Arabic and other RTL scripts get a larger size and room for harakat. */
@Composable
internal fun pieceStyle(language: String): TextStyle =
    if (Words.isRtl(language)) MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 40.sp)
    else MaterialTheme.typography.bodyLarge

/** Lays out [content] right to left for RTL languages. */
@Composable
internal fun Directional(language: String, content: @Composable () -> Unit) {
    if (Words.isRtl(language)) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    else content()
}

@Composable
internal fun Badge(label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, contentColor = contentColorFor(color), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
