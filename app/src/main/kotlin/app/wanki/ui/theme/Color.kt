package app.wanki.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Wanki's own palette: notebook paper, warm near-black ink, a sienna accent like a pencil or brown ink,
 * muted sage as the second colour and a faded ink blue as the third. The dark scheme is dark coffee, not grey.
 */

// Light: cream paper
private val Paper = Color(0xFFFBF4E6)
private val Ink = Color(0xFF2B2420)
private val Sienna = Color(0xFFA0522D)
private val Sage = Color(0xFF66704F)
private val InkBlue = Color(0xFF4F6170)

// Dark: dark coffee
private val Coffee = Color(0xFF231D18)
private val CreamInk = Color(0xFFEFE4D3)
private val SiennaLight = Color(0xFFE39A6A)
private val SageLight = Color(0xFFC3CBA6)
private val InkBlueLight = Color(0xFFA9C5DA)

val WankiLightColors: ColorScheme = lightColorScheme(
    primary = Sienna,
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFF6D9C2),
    onPrimaryContainer = Color(0xFF3D1A05),
    inversePrimary = Color(0xFFFFB68A),
    secondary = Sage,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7CC),
    onSecondaryContainer = Color(0xFF20260F),
    tertiary = InkBlue,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E2EE),
    onTertiaryContainer = Color(0xFF0B1D2A),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE1CD),
    onSurfaceVariant = Color(0xFF5E5246),
    // tonal elevation darkens a sheet towards kraft paper instead of tinting it orange
    surfaceTint = Color(0xFF8A6A4F),
    inverseSurface = Color(0xFF3A322B),
    inverseOnSurface = Color(0xFFF7EEDF),
    error = Color(0xFFB3392F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DAD4),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8E7F70),
    outlineVariant = Color(0xFFD8CAB5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFBF3),
    surfaceDim = Color(0xFFE9DFCC),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFFFF9EE),
    surfaceContainer = Color(0xFFF5ECDB),
    surfaceContainerHigh = Color(0xFFEFE5D2),
    surfaceContainerHighest = Color(0xFFE9DECA),
)

val WankiDarkColors: ColorScheme = darkColorScheme(
    primary = SiennaLight,
    onPrimary = Color(0xFF4A2008),
    primaryContainer = Color(0xFF7A3E1A),
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = Sienna,
    secondary = SageLight,
    onSecondary = Color(0xFF2E3519),
    secondaryContainer = Color(0xFF454D30),
    onSecondaryContainer = Color(0xFFDFE7C1),
    tertiary = InkBlueLight,
    onTertiary = Color(0xFF133243),
    tertiaryContainer = Color(0xFF2F4858),
    onTertiaryContainer = Color(0xFFD3E6F4),
    background = Coffee,
    onBackground = CreamInk,
    surface = Coffee,
    onSurface = CreamInk,
    surfaceVariant = Color(0xFF4D4237),
    onSurfaceVariant = Color(0xFFD6C6B4),
    surfaceTint = Color(0xFFC8A88A),
    inverseSurface = CreamInk,
    inverseOnSurface = Color(0xFF362F29),
    error = Color(0xFFEE9F92),
    onError = Color(0xFF5C1610),
    errorContainer = Color(0xFF8A2A20),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF9F8F7E),
    outlineVariant = Color(0xFF4D4237),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF4A4038),
    surfaceDim = Color(0xFF1C1713),
    surfaceContainerLowest = Color(0xFF1A1511),
    surfaceContainerLow = Color(0xFF2B241E),
    surfaceContainer = Color(0xFF2F2822),
    surfaceContainerHigh = Color(0xFF3A322B),
    surfaceContainerHighest = Color(0xFF453C34),
)
