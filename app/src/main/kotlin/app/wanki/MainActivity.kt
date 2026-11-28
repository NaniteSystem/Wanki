package app.wanki

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.wanki.input.InkTiles
import app.wanki.ui.MainShell
import app.wanki.ui.theme.WankiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isDarkTheme()
            // status/navigation bar icons follow the app theme, which may differ from the system's
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            WankiTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { MainShell() }
                }
            }
        }
    }

    /** Tile bitmaps are a cache: give their memory back when the system asks. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        InkTiles.trimAll()
    }

    private companion object {
        // the theme's paper and coffee backgrounds (ui/theme/Color.kt)
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFB, 0xF4, 0xE6)
        val DARK_SCRIM = Color.argb(0x80, 0x23, 0x1D, 0x18)
    }
}

/** The theme chosen in settings: system, light or dark. */
@Composable
fun isDarkTheme(): Boolean = when (LocalContext.current.graph.settings.theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}
