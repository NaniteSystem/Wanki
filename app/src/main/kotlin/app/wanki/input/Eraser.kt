package app.wanki.input

/** Whether the eraser removes whole strokes it touches or only the part under it. */
enum class EraserMode { WHOLE, PARTIAL }

/** Eraser radius; [radiusDp] is on screen, whatever the zoom. */
enum class EraserSize(val radiusDp: Float) { S(8f), M(16f), L(32f) }

data class EraserSettings(val mode: EraserMode = EraserMode.PARTIAL, val size: EraserSize = EraserSize.M) {
    companion object
}

/** "MODE:SIZE", e.g. `PARTIAL:M`. */
fun EraserSettings.encode(): String = "${mode.name}:${size.name}"

/** Inverse of [encode]; anything unreadable gives the default. */
fun EraserSettings.Companion.decode(s: String?): EraserSettings {
    val parts = s?.split(':') ?: return EraserSettings()
    if (parts.size != 2) return EraserSettings()
    val mode = EraserMode.entries.firstOrNull { it.name == parts[0] } ?: return EraserSettings()
    val size = EraserSize.entries.firstOrNull { it.name == parts[1] } ?: return EraserSettings()
    return EraserSettings(mode, size)
}

/** Where the eraser is on screen while erasing: centre and radius in px. */
data class EraserCursor(val x: Float, val y: Float, val radius: Float)
