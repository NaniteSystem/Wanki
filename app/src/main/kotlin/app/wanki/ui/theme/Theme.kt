package app.wanki.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.wanki.isDarkTheme

/** Wanki's own warm paper theme (no Material You dynamic colours); light or dark as chosen in settings. */
@Composable
fun WankiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme()) WankiDarkColors else WankiLightColors,
        typography = WankiTypography,
        shapes = WankiShapes,
        content = content,
    )
}
