package app.wanki.input

import app.wanki.ink.BrushKind
import app.wanki.ink.InkBrush

enum class ToolKind { PEN, MARKER, HIGHLIGHTER, ERASER, LASSO }

/** What the toolbar has selected. The pen's eraser end and its side button always erase. */
data class ToolSelection(
    val kind: ToolKind = ToolKind.PEN,
    val color: Int = DEFAULT_INK,
    /** Brush width in world units. */
    val size: Float = 4f,
) {
    val isEraser: Boolean get() = kind == ToolKind.ERASER
    val isLasso: Boolean get() = kind == ToolKind.LASSO

    fun brush(): InkBrush = when (kind) {
        ToolKind.PEN, ToolKind.ERASER, ToolKind.LASSO -> InkBrush(BrushKind.PEN, color, size)
        ToolKind.MARKER -> InkBrush(BrushKind.MARKER, color, size * 1.6f)
        ToolKind.HIGHLIGHTER -> InkBrush(BrushKind.HIGHLIGHTER, (color and 0x00FFFFFF) or 0x66000000, size * 5f)
    }

    companion object {
        const val DEFAULT_INK = 0xFF1A1A1A.toInt()
        val PALETTE = listOf(
            DEFAULT_INK, 0xFF5F6368.toInt(), 0xFF1E5BD8.toInt(), 0xFFD23C3C.toInt(),
            0xFF2E9E5B.toInt(), 0xFFE0A100.toInt(), 0xFF8E44AD.toInt(), 0xFFE67E22.toInt(),
        )
        val SIZES = listOf(1.5f, 2.5f, 4f, 7f, 11f)
    }
}

/** "KIND:color:size"; the colour is stored as a signed int. */
fun ToolSelection.encode(): String = "${kind.name}:$color:$size"

/** Inverse of [encode]; anything unreadable gives the default selection. */
fun ToolSelection.Companion.decode(s: String?): ToolSelection {
    val parts = s?.split(':') ?: return ToolSelection()
    if (parts.size != 3) return ToolSelection()
    val kind = ToolKind.entries.firstOrNull { it.name == parts[0] } ?: return ToolSelection()
    val color = parts[1].toIntOrNull() ?: return ToolSelection()
    val size = parts[2].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return ToolSelection()
    return ToolSelection(kind, color, size)
}

/** Which kind of ink surface a remembered tool belongs to. */
enum class InkSection { NOTEBOOK, DIARY, BOARD, NOTE;
    companion object {
        fun ofNotebookKind(kind: String): InkSection = entries.firstOrNull { it.name == kind } ?: NOTEBOOK
    }
}
