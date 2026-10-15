package app.wanki.scoring

import app.wanki.scoring.geom.Polyline

/** One reference stroke in the reference coordinate square (KanjiVG: 109×109, y down). */
data class RefStroke(
    val points: Polyline,
    /** Stroke type as given by the source, e.g. KanjiVG's "㇒" or "㇕b". */
    val type: String? = null,
)

/** A part of the character (radical / component). Used for proportion scoring. */
data class Component(
    val element: String?,
    /** Indices into [CharReference.strokes], all descendants included. */
    val strokeIndices: List<Int>,
    val children: List<Component> = emptyList(),
)

data class CharReference(
    val char: String,
    val strokes: List<RefStroke>,
    val root: Component = Component(char, strokes.indices.toList()),
    /** Side of the reference coordinate square. */
    val size: Double = 109.0,
    val source: String = "unknown",
) {
    /**
     * Top-level parts of the character: descends through single-child wrappers and returns the
     * first level that splits into several components. Empty if the character has no split.
     */
    fun topLevelParts(): List<Component> {
        var node = root
        while (true) {
            val kids = node.children.filter { it.strokeIndices.isNotEmpty() }
            when {
                kids.size >= 2 -> return kids
                kids.size == 1 && kids[0].strokeIndices.size == node.strokeIndices.size -> node = kids[0]
                else -> return emptyList()
            }
        }
    }
}
