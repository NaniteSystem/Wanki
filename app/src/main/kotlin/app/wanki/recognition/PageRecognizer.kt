package app.wanki.recognition

import app.wanki.data.PageLineEntity
import app.wanki.ink.BrushKind
import app.wanki.ink.InkRect
import app.wanki.ink.InkStroke
import app.wanki.ink.LineKeys
import app.wanki.ink.LineSegmenter

/** What of a page's lines is unchanged and what must go to the recognizer again. */
object LinePlan {
    /** A line found on the page now: [key] is [LineKeys.key] of its strokes, [index] its place from the top. */
    data class LineInput(val key: Long, val index: Int, val bounds: InkRect)

    /** [keep]: stored rows of unchanged lines, with their new index and bounds (a correction stays). [recognize]: the rest. */
    data class Plan(val keep: List<PageLineEntity>, val recognize: List<LineInput>)

    fun plan(lines: List<LineInput>, stored: List<PageLineEntity>): Plan {
        val byKey = stored.associateBy { it.lineKey }
        val seen = HashSet<Long>()
        val keep = ArrayList<PageLineEntity>()
        val recognize = ArrayList<LineInput>()
        for (l in lines) {
            if (!seen.add(l.key)) continue // identical strokes in the same place: one row per key
            val old = byKey[l.key]
            if (old != null) {
                keep += old.copy(lineIndex = l.index, left = l.bounds.left, top = l.bounds.top, right = l.bounds.right, bottom = l.bounds.bottom)
            } else recognize += l
        }
        return Plan(keep, recognize)
    }
}

/** Text matching for the search highlight: case-insensitive, ё = е. */
object LineMatch {
    fun fold(s: String): String = s.lowercase().replace('ё', 'е')
    fun matches(shown: String, query: String): Boolean = query.isNotBlank() && fold(shown).contains(fold(query.trim()))
}

/**
 * The page's searchable text while some lines could not be recognised: what failed keeps its old content, so a
 * failed recognition never takes text out of search.
 */
object SearchText {
    /** Failed lines: where [dropped] (stored rows that no longer match any line) have a row at the same index, it fills the place. */
    fun build(lines: List<PageLineEntity>, failedIndexes: List<Int>, dropped: List<PageLineEntity>, oldText: String): String {
        fun join(l: List<PageLineEntity>) = l.sortedBy { it.lineIndex }.joinToString("\n") { it.shown }
        if (failedIndexes.isEmpty()) return join(lines)
        val fill = failedIndexes.map { i -> dropped.firstOrNull { it.lineIndex == i } }
        // a failed line without an old row (a legacy page, or one that failed before): the old text stays as it was
        if (fill.any { it == null }) return oldText
        return join(lines + fill.filterNotNull())
    }
}

/** Recognizes only the lines of a page whose strokes changed since [stored]; corrections of unchanged lines stay. */
class PageRecognizer(private val text: LineRecognizer) {
    /** [lines] are the page's lines now (kept and recognized, by index); [failed] lines could not be recognized and are left out. */
    data class Result(val lines: List<PageLineEntity>, val failed: Int, val failedIndexes: List<Int> = emptyList())

    suspend fun recognize(
        strokes: List<InkStroke>,
        keyOf: (InkStroke) -> Long,
        stored: List<PageLineEntity>,
        language: String,
        pageId: String,
    ): Result {
        val writing = strokes.filter { it.brush.kind != BrushKind.HIGHLIGHTER && it.points.isNotEmpty() }
        val groups = LineSegmenter.segment(writing).map { g -> g.map { writing[it] } }
        val inputs = groups.mapIndexedNotNull { i, g ->
            val boxes = g.mapNotNull { it.bounds() }
            if (boxes.isEmpty()) null else LinePlan.LineInput(LineKeys.key(g.map(keyOf)), i, boxes.reduce(InkRect::union))
        }
        val groupOf = groups.withIndex().associate { it.index to it.value }
        val plan = LinePlan.plan(inputs, stored.filter { it.pageId == pageId })
        val langs = text.candidateLanguages(language)
        val shown = HashMap<Int, String>() // index -> shown text, the pre-context of the next line
        plan.keep.forEach { shown[it.lineIndex] = it.shown }
        val done = ArrayList<PageLineEntity>(plan.keep)
        val failedAt = ArrayList<Int>()
        val now = System.currentTimeMillis()
        for (l in plan.recognize.sortedBy { it.index }) {
            val b = l.bounds
            val pre = shown.entries.filter { it.key < l.index }.maxByOrNull { it.key }?.value.orEmpty()
            val got = text.recognizeLine(groupOf.getValue(l.index), b.width * 1.2f, b.height * 1.4f, pre, langs, true)
            if (got == null) { failedAt += l.index; continue }
            shown[l.index] = got.first
            done += PageLineEntity(pageId, l.key, l.index, b.left, b.top, b.right, b.bottom, got.first, null, now)
        }
        return Result(done.sortedBy { it.lineIndex }, failedAt.size, failedAt)
    }
}
