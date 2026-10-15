package app.wanki.recognition

import app.wanki.ink.InkStroke
import app.wanki.ink.LineSegmenter

/**
 * Turns a handwritten page into text: strokes are split into lines ([LineSegmenter]) and each
 * line is recognized with the previous text as context. Highlighter strokes are ignored.
 */
class PageRecognizer(private val recognizer: HandwritingRecognizer) {
    suspend fun recognize(strokes: List<InkStroke>, pageWidth: Float): String? {
        val writing = strokes.filter { it.brush.kind != app.wanki.ink.BrushKind.HIGHLIGHTER }
        if (writing.isEmpty()) return ""
        val lines = LineSegmenter.segment(writing)
        val out = StringBuilder()
        for (line in lines) {
            val lineStrokes = line.map { writing[it] }
            val top = lineStrokes.mapNotNull { it.bounds()?.top }.min()
            val bottom = lineStrokes.mapNotNull { it.bounds()?.bottom }.max()
            val text = recognizer.recognize(lineStrokes, pageWidth, (bottom - top) * 1.3f, out.toString())
                ?: return null
            if (out.isNotEmpty()) out.append('\n')
            out.append(text.firstOrNull().orEmpty())
        }
        return out.toString()
    }
}
