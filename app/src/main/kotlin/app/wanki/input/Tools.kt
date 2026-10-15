package app.wanki.input

import app.wanki.ink.BrushKind
import app.wanki.ink.InkBrush

enum class ToolKind { PEN, MARKER, HIGHLIGHTER, ERASER }

/** What the toolbar has selected. The pen's eraser end and its side button always erase. */
data class ToolSelection(
    val kind: ToolKind = ToolKind.PEN,
    val color: Int = DEFAULT_INK,
    /** Brush width in world units. */
    val size: Float = 4f,
) {
    val isEraser: Boolean get() = kind == ToolKind.ERASER

    fun brush(): InkBrush = when (kind) {
        ToolKind.PEN, ToolKind.ERASER -> InkBrush(BrushKind.PEN, color, size)
        ToolKind.MARKER -> InkBrush(BrushKind.MARKER, color, size * 1.6f)
        ToolKind.HIGHLIGHTER -> InkBrush(BrushKind.HIGHLIGHTER, (color and 0x00FFFFFF) or 0x66000000, size * 5f)
    }

    companion object {
        const val DEFAULT_INK = 0xFF1A1A1A.toInt()
        val PALETTE = listOf(DEFAULT_INK, 0xFF1E5BD8.toInt(), 0xFFD23C3C.toInt(), 0xFF2E9E5B.toInt(), 0xFFE0A100.toInt())
        val SIZES = listOf(2.5f, 4f, 7f)
    }
}
