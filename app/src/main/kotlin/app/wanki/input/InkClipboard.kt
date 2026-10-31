package app.wanki.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke

/** Copied strokes and page objects, placed relative to the top-left corner of their bounds. */
data class InkClip(val strokes: List<InkStroke>, val objects: List<PageObject> = emptyList()) {
    fun bounds(): InkRect? = (strokes.mapNotNull { it.bounds() } + objects.map { it.bounds() }).reduceOrNull { a, b -> a.union(b) }
    val isEmpty: Boolean get() = strokes.isEmpty() && objects.isEmpty()
}

/**
 * The app-wide ink clipboard (Decision 8): in memory only, so it survives switching pages and notebooks but not
 * process death. Compose state, so «Вставить» appears as soon as something is copied.
 */
object InkClipboard {
    var clip by mutableStateOf<InkClip?>(null)
}
