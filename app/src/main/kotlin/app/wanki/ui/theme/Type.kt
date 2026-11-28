package app.wanki.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle

/*
 * Display, headline and title styles use the system serif for a notebook/journal feel; body and label styles keep
 * the system sans for long texts and Japanese content. Both are system families (Noto Serif / Roboto with the
 * platform's CJK fallback), so Cyrillic and Japanese need no bundled fonts.
 */

private val Base = Typography()

private fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)

val WankiTypography: Typography = Base.copy(
    displayLarge = Base.displayLarge.serif(),
    displayMedium = Base.displayMedium.serif(),
    displaySmall = Base.displaySmall.serif(),
    headlineLarge = Base.headlineLarge.serif(),
    headlineMedium = Base.headlineMedium.serif(),
    headlineSmall = Base.headlineSmall.serif(),
    titleLarge = Base.titleLarge.serif(),
    titleMedium = Base.titleMedium.serif(),
    titleSmall = Base.titleSmall.serif(),
)
